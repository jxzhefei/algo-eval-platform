package com.algoeval.ai.report;

import com.algoeval.ai.llm.LlmClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 解读报告（V3 方向三 5.1，优先级最高的 AI 切入点）。
 * <p>
 * 生成管线：结构化指标聚合 → 归因分析（维度贡献分解）→ 受限生成（非自由发挥）。
 * <ul>
 *   <li>归因：整体绩效偏差 = Σ（桶内偏差 × 桶占比）——找出最大拖累桶与最大优势桶，数字可追溯</li>
 *   <li>受限生成：默认本地模板引擎（确定性文本）；配置私有 LLM 后由 LLM 基于结构化数据润写，
 *       system 提示词锁死"只允许引用给定数字"——幻觉风险可控，券商风控可过</li>
 * </ul>
 */
@Service
public class AiReportService {

    private final JdbcTemplate jdbcTemplate;
    private final LlmClient llmClient;

    public AiReportService(JdbcTemplate jdbcTemplate, LlmClient llmClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmClient = llmClient;
    }

    /** 归因发现 */
    public record Finding(String dimension, String bucket, BigDecimal metricValue,
                          BigDecimal contributionBps, int sampleCount) {
    }

    /** 结构化画像数据（报告的事实基础） */
    public record StructuredProfile(String algoInstance, LocalDate from, LocalDate to,
                                    int days, long totalOrders, BigDecimal avgVwapBps,
                                    BigDecimal avgTwapBps, BigDecimal avgCancelRatio,
                                    BigDecimal avgFillRatio, BigDecimal avgScore,
                                    List<Map<String, Object>> trend,
                                    List<Finding> participationFindings,
                                    List<Finding> sideFindings,
                                    Map<String, Object> bestDay, Map<String, Object> worstDay,
                                    List<Map<String, Object>> worstSymbols,
                                    List<Map<String, Object>> bestSymbols) {
    }

    public Map<String, Object> report(String instance, LocalDate from, LocalDate to) {
        StructuredProfile profile = aggregate(instance, from, to);
        if (profile.days() == 0) {
            return Map.of("algoInstance", instance, "error", "该期间无评价数据（等待盘后离线计算产出）");
        }
        List<String> localNarrative = localNarrative(profile);
        String narrative = llmNarrative(profile);
        if (narrative == null) {
            narrative = String.join("\n", localNarrative);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algoInstance", instance);
        result.put("from", profile.from().toString());
        result.put("to", profile.to().toString());
        result.put("llmPowered", llmClient.available());
        result.put("profile", profile);
        result.put("narrative", narrative);
        result.put("suggestions", suggestions(profile));
        return result;
    }

    /**
     * 结构化聚合：事实基础。所有数字来自评价结果表，可追溯到母单。
     */
    StructuredProfile aggregate(String instance, LocalDate from, LocalDate to) {
        List<Map<String, Object>> trend = jdbcTemplate.queryForList(
                "SELECT trade_date, order_count, avg_vwap_bps, avg_twap_bps, avg_cancel_ratio, "
                        + "avg_fill_ratio, total_score FROM algo_daily_summary "
                        + "WHERE algo_instance=? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
                instance, Date.valueOf(from), Date.valueOf(to));
        if (trend.isEmpty()) {
            return new StructuredProfile(instance, from, to, 0, 0, null, null, null, null, null,
                    trend, List.of(), List.of(), Map.of(), Map.of(), List.of(), List.of());
        }

        // 母单级指标（归因用）
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT r.vwap_perf_bps, r.cancel_ratio, r.fill_ratio, r.participation, p.side, p.symbol "
                        + "FROM indicator_result r JOIN parent_order p "
                        + "ON r.order_id = p.order_id AND r.trade_date = p.trade_date "
                        + "WHERE r.algo_instance=? AND r.trade_date BETWEEN ? AND ?",
                instance, Date.valueOf(from), Date.valueOf(to));

        long totalOrders = orders.size();
        BigDecimal overallVwap = avg(orders, "vwap_perf_bps".toUpperCase());
        // H2 返回列名大写
        String vwapCol = columnOf(orders, "VWAP_PERF_BPS", "vwap_perf_bps");

        // 参与率分桶归因：贡献 = (桶均值 - 整体均值) × 桶占比
        List<Finding> partFindings = attribute(orders, vwapCol, overallVwap, row -> {
            BigDecimal p = toBd(row.get(columnOf(orders, "PARTICIPATION", "participation")));
            if (p == null) {
                return "[0,0.05%)";
            }
            if (p.compareTo(new BigDecimal("0.0005")) < 0) {
                return "[0,0.05%)";
            }
            return p.compareTo(new BigDecimal("0.002")) < 0 ? "[0.05,0.2%)" : "[0.2,100]";
        });

        // 方向归因
        List<Finding> sideFindings = attribute(orders, vwapCol, overallVwap,
                row -> String.valueOf(row.get(columnOf(orders, "SIDE", "side"))));

        // 标的极值（样本 ≥ 3）
        Map<String, List<Map<String, Object>>> bySymbol = new LinkedHashMap<>();
        for (Map<String, Object> row : orders) {
            bySymbol.computeIfAbsent(String.valueOf(row.get(columnOf(orders, "SYMBOL", "symbol"))),
                    k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> symbolStats = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : bySymbol.entrySet()) {
            if (e.getValue().size() >= 3) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("symbol", e.getKey());
                s.put("orderCount", e.getValue().size());
                s.put("avgVwapBps", avg(e.getValue(), vwapCol));
                symbolStats.add(s);
            }
        }
        symbolStats.sort(Comparator.comparing(s -> toBd(s.get("avgVwapBps"))));
        List<Map<String, Object>> worstSymbols = symbolStats.stream().limit(3).toList();
        List<Map<String, Object>> bestSymbols = symbolStats.stream()
                .skip(Math.max(0, symbolStats.size() - 3)).sorted(
                        Comparator.comparing((Map<String, Object> s) -> toBd(s.get("avgVwapBps"))).reversed())
                .toList();

        Map<String, Object> bestDay = trend.stream().min(Comparator.comparing(r -> absBd(r.get("avg_vwap_bps")))).orElse(Map.of());
        Map<String, Object> worstDay = trend.stream().max(Comparator.comparing(r -> absBd(r.get("avg_vwap_bps")))).orElse(Map.of());

        BigDecimal sumScore = BigDecimal.ZERO;
        BigDecimal sumCancel = BigDecimal.ZERO;
        BigDecimal sumFill = BigDecimal.ZERO;
        BigDecimal sumTwap = BigDecimal.ZERO;
        for (Map<String, Object> row : trend) {
            sumScore = sumScore.add(nvl(toBd(row.get("total_score"))));
            sumCancel = sumCancel.add(nvl(toBd(row.get("avg_cancel_ratio"))));
            sumFill = sumFill.add(nvl(toBd(row.get("avg_fill_ratio"))));
            sumTwap = sumTwap.add(nvl(toBd(row.get("avg_twap_bps"))));
        }
        int days = trend.size();
        return new StructuredProfile(instance, from, to, days, totalOrders,
                overallVwap, sumTwap.divide(BigDecimal.valueOf(days), 4, RoundingMode.HALF_UP),
                sumCancel.divide(BigDecimal.valueOf(days), 4, RoundingMode.HALF_UP),
                sumFill.divide(BigDecimal.valueOf(days), 4, RoundingMode.HALF_UP),
                sumScore.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP),
                trend, partFindings, sideFindings, bestDay, worstDay, worstSymbols, bestSymbols);
    }

    /** 维度归因：返回按 |贡献| 降序的发现列表 */
    private List<Finding> attribute(List<Map<String, Object>> orders, String metricCol,
                                    BigDecimal overall, java.util.function.Function<Map<String, Object>, String> bucketFn) {
        Map<String, List<BigDecimal>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : orders) {
            BigDecimal v = toBd(row.get(metricCol));
            if (v == null) {
                continue;
            }
            buckets.computeIfAbsent(bucketFn.apply(row), k -> new ArrayList<>()).add(v);
        }
        int total = buckets.values().stream().mapToInt(List::size).sum();
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<BigDecimal>> e : buckets.entrySet()) {
            BigDecimal bucketAvg = e.getValue().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(e.getValue().size()), 4, RoundingMode.HALF_UP);
            BigDecimal share = BigDecimal.valueOf(e.getValue().size())
                    .divide(BigDecimal.valueOf(Math.max(1, total)), 4, RoundingMode.HALF_UP);
            BigDecimal contribution = bucketAvg.subtract(overall).multiply(share)
                    .setScale(4, RoundingMode.HALF_UP);
            findings.add(new Finding("", e.getKey(), bucketAvg, contribution, e.getValue().size()));
        }
        findings.sort(Comparator.comparing((Finding f) -> f.contributionBps().abs()).reversed());
        return findings;
    }

    /**
     * 本地受限生成：确定性模板叙事（零依赖轨道的完整降级）。
     * 文本结构：总体表现 → 归因分析 → 极值与建议方向。
     */
    private List<String> localNarrative(StructuredProfile p) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("【总体表现】%s 至 %s 期间，算法 %s 运行 %d 个交易日、母单 %d 笔，"
                        + "平均 VWAP 绩效 %s bps（越小越好），平均完成率 %s%%，撤单率 %s%%，综合得分 %s 分。",
                p.from(), p.to(), p.algoInstance(), p.days(), p.totalOrders(),
                p.avgVwapBps().toPlainString(),
                p.avgFillRatio().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString(),
                p.avgCancelRatio().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString(),
                p.avgScore().toPlainString()));

        if (!p.participationFindings().isEmpty()) {
            Finding top = p.participationFindings().get(0);
            String verdict = top.contributionBps().signum() > 0 ? "拖累" : "贡献优势";
            lines.add(String.format("【归因分析】绩效偏离的最大来源是市占率%s区间（样本 %d 笔）："
                            + "该区间平均绩效 %s bps，对整体绩效的%s为 %s bps。",
                    top.bucket(), top.sampleCount(), top.metricValue().toPlainString(),
                    verdict, top.contributionBps().abs().toPlainString()));
        }
        if (!p.sideFindings().isEmpty()) {
            Finding side = p.sideFindings().get(0);
            lines.add(String.format("方向维度上，%s 方向样本 %d 笔、平均绩效 %s bps，是方向维度的主要偏离来源（贡献 %s bps）。",
                    side.bucket(), side.sampleCount(), side.metricValue().toPlainString(),
                    side.contributionBps().toPlainString()));
        }
        if (!p.worstSymbols().isEmpty()) {
            Map<String, Object> w = p.worstSymbols().get(0);
            lines.add(String.format("标的维度：表现最弱的标的为 %s（%d 笔，平均 %s bps）；表现最好的为 %s（平均 %s bps）。",
                    w.get("symbol"), ((Number) w.get("orderCount")).intValue(),
                    toBd(w.get("avgVwapBps")).toPlainString(),
                    p.bestSymbols().isEmpty() ? "-" : p.bestSymbols().get(0).get("symbol"),
                    p.bestSymbols().isEmpty() ? "-" : toBd(p.bestSymbols().get(0).get("avgVwapBps")).toPlainString()));
        }
        if (!p.bestDay().isEmpty()) {
            lines.add(String.format("最优交易日 %s（%s bps / %s 分），最差交易日 %s（%s bps / %s 分）。",
                    p.bestDay().getOrDefault("trade_date", "-"),
                    toBd(p.bestDay().get("avg_vwap_bps")).toPlainString(),
                    toBd(p.bestDay().get("total_score")).toPlainString(),
                    p.worstDay().getOrDefault("trade_date", "-"),
                    toBd(p.worstDay().get("avg_vwap_bps")).toPlainString(),
                    toBd(p.worstDay().get("total_score")).toPlainString()));
        }
        return lines;
    }

    /** LLM 受限生成：结构化数据 + 锁死边界的 system 提示词 */
    private String llmNarrative(StructuredProfile p) {
        if (!llmClient.available()) {
            return null;
        }
        String system = "你是算法交易执行质量分析师。严格基于用户提供的结构化数据撰写解读报告，"
                + "禁止编造任何未给出的数字；绩效指标 VWAP bps 数值越小越好（正=跑输基准）。"
                + "输出三段：总体表现、归因分析、行动建议。总字数 300 字以内。";
        String user = String.format(
                "算法=%s；区间=%s~%s（%d 日）；母单=%d；平均VWAP绩效=%s bps；"
                        + "完成率=%s；撤单率=%s；综合得分=%s；"
                        + "市占率分桶归因=%s；方向归因=%s；最差标的=%s；最优标的=%s",
                p.algoInstance(), p.from(), p.to(), p.days(), p.totalOrders(),
                p.avgVwapBps().toPlainString(), p.avgFillRatio().toPlainString(),
                p.avgCancelRatio().toPlainString(), p.avgScore().toPlainString(),
                p.participationFindings(), p.sideFindings(),
                p.worstSymbols(), p.bestSymbols());
        return llmClient.complete(system, user);
    }

    /** 可执行建议（规则映射：归因发现 → 干预动作） */
    private List<String> suggestions(StructuredProfile p) {
        List<String> tips = new ArrayList<>();
        if (!p.participationFindings().isEmpty()) {
            Finding top = p.participationFindings().get(0);
            if (top.contributionBps().signum() > 0 && top.bucket().startsWith("[0.2")) {
                tips.add("高市占率区间是主要绩效拖累：建议对该类大额母单限制参与率上限（如 ≤0.2%），或拆分为多笔委托降低单笔冲击。");
            } else if (top.contributionBps().signum() < 0) {
                tips.add(String.format("%s区间是绩效优势区间：建议该流动性场景下优先派单给本算法实例。", top.bucket()));
            }
        }
        if (p.avgCancelRatio().compareTo(new BigDecimal("0.12")) > 0) {
            tips.add("撤单率高于 12%：建议检查追价逻辑与报价偏移参数，减少无效挂撤单。");
        }
        if (p.avgFillRatio().compareTo(new BigDecimal("0.90")) < 0) {
            tips.add("完成率低于 90%：建议评估任务时限设置或启用收盘前集中补单机制。");
        }
        if (tips.isEmpty()) {
            tips.add("各项指标处于健康区间：维持当前参数，持续跟踪能力矩阵的边际变化。");
        }
        return tips;
    }

    private static BigDecimal avg(List<Map<String, Object>> rows, String col) {
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (Map<String, Object> row : rows) {
            BigDecimal v = toBd(row.get(col));
            if (v != null) {
                sum = sum.add(v);
                n++;
            }
        }
        return n == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private static String columnOf(List<Map<String, Object>> rows, String upper, String lower) {
        if (rows.isEmpty()) {
            return lower;
        }
        return rows.get(0).containsKey(upper) ? upper : lower;
    }

    private static BigDecimal toBd(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal absBd(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()).abs();
    }
}
