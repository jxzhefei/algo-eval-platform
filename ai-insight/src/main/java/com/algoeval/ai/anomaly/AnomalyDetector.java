package com.algoeval.ai.anomaly;

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
 * 绩效离群母单检测（V3 方向三 5.3，旗舰档可选能力的标准档实现）。
 * <p>
 * 方法：分位数级别的无监督检测——MAD 稳健 Z 分数（中位数绝对偏差），
 * 刻意不用深度学习：可解释（"偏离该算法历史中位数 N 倍 MAD"）、可在客户机房 CPU 上跑。
 * <p>
 * 多维检测：VWAP绩效 / 撤单率 / 完成率 / 市场冲击四个维度各自算稳健 Z，
 * 加权合成异常分（权重=五维评分口径中风险相关维度的占比）。
 */
@Service
public class AnomalyDetector {

    /** 稳健 Z 阈值：|z| ≥ 3.5 视为离群（MAD 检验标准阈值） */
    private static final double OUTLIER_Z = 3.5;
    /** 维度权重（合成异常分）：绩效最重，撤单/完成/冲击次之 */
    private static final double W_VWAP = 0.4;
    private static final double W_CANCEL = 0.2;
    private static final double W_FILL = 0.2;
    private static final double W_IMPACT = 0.2;

    private final JdbcTemplate jdbcTemplate;

    public AnomalyDetector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 离群检测结果行 */
    public record Outlier(long orderId, LocalDate tradeDate, String algoInstance, String symbol, String side,
                          BigDecimal vwapPerfBps, BigDecimal cancelRatio, BigDecimal fillRatio,
                          BigDecimal impactBps, double anomalyScore, String severity,
                          String mainDimension, double mainDimensionZ, String explanation) {
    }

    /** 检测报告 */
    public record AnomalyReport(LocalDate date, int scannedOrders, int outlierCount,
                                List<Outlier> outliers, List<Map<String, Object>> dimensionStats) {
    }

    /**
     * 检测某交易日（或区间）的绩效离群母单：按算法实例分组计算各维度稳健 Z。
     *
     * @param algo 可选：仅检测该算法实例；空 = 全部
     */
    public AnomalyReport detect(LocalDate from, LocalDate to, String algo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.order_id, r.trade_date, r.algo_instance, r.vwap_perf_bps, r.cancel_ratio, "
                        + "r.fill_ratio, r.impact_bps, p.symbol, p.side "
                        + "FROM indicator_result r JOIN parent_order p "
                        + "ON r.order_id = p.order_id AND r.trade_date = p.trade_date "
                        + "WHERE r.trade_date BETWEEN ? AND ?"
                        + (algo == null || algo.isBlank() ? "" : " AND r.algo_instance='" + algo.replace("'", "") + "'"),
                Date.valueOf(from), Date.valueOf(to));

        // 按算法分组：离群是相对"该算法自己的分布"而言
        Map<String, List<Map<String, Object>>> byAlgo = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byAlgo.computeIfAbsent((String) row.get("algo_instance"), k -> new ArrayList<>()).add(row);
        }

        List<Outlier> outliers = new ArrayList<>();
        List<Map<String, Object>> dimensionStats = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byAlgo.entrySet()) {
            List<Map<String, Object>> algoRows = e.getValue();
            Dist vwapDist = distOf(algoRows, "vwap_perf_bps");
            Dist cancelDist = distOf(algoRows, "cancel_ratio");
            Dist fillDist = distOf(algoRows, "fill_ratio");
            Dist impactDist = distOf(algoRows, "impact_bps");
            dimensionStats.add(Map.of(
                    "algoInstance", e.getKey(),
                    "sampleCount", algoRows.size(),
                    "vwapMedian", vwapDist.median,
                    "vwapMad", vwapDist.mad,
                    "impactMedian", impactDist.median,
                    "impactMad", impactDist.mad));

            for (Map<String, Object> row : algoRows) {
                double zVwap = z(vwapDist, row.get("vwap_perf_bps"));
                double zCancel = z(cancelDist, row.get("cancel_ratio"));
                double zFill = z(fillDist, row.get("fill_ratio"));
                double zImpact = z(impactDist, row.get("impact_bps"));
                double score = Math.sqrt(W_VWAP * zVwap * zVwap + W_CANCEL * zCancel * zCancel
                        + W_FILL * zFill * zFill + W_IMPACT * zImpact * zImpact);
                if (score < OUTLIER_Z) {
                    continue;
                }
                // 主偏离维度
                String mainDim = "VWAP绩效";
                double mainZ = zVwap;
                if (Math.abs(zCancel) > Math.abs(mainZ)) {
                    mainDim = "撤单率";
                    mainZ = zCancel;
                }
                if (Math.abs(zFill) > Math.abs(mainZ)) {
                    mainDim = "完成率";
                    mainZ = zFill;
                }
                if (Math.abs(zImpact) > Math.abs(mainZ)) {
                    mainDim = "市场冲击";
                    mainZ = zImpact;
                }
                String severity = Math.abs(mainZ) >= 6 ? "HIGH" : Math.abs(mainZ) >= 4.5 ? "MEDIUM" : "LOW";
                outliers.add(new Outlier(
                        ((Number) row.get("order_id")).longValue(),
                        ((java.sql.Date) row.get("trade_date")).toLocalDate(),
                        e.getKey(), (String) row.get("symbol"), (String) row.get("side"),
                        toBd(row.get("vwap_perf_bps")), toBd(row.get("cancel_ratio")),
                        toBd(row.get("fill_ratio")), toBd(row.get("impact_bps")),
                        BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                        severity, mainDim, BigDecimal.valueOf(mainZ).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                        String.format("母单 %d（%s %s）：%s偏离本算法历史中位数 %.1f 倍 MAD，合成异常分 %.1f。",
                                ((Number) row.get("order_id")).longValue(),
                                row.get("side"), row.get("symbol"), mainDim, Math.abs(mainZ), score)));
            }
        }
        outliers.sort(Comparator.comparingDouble(Outlier::anomalyScore).reversed());
        return new AnomalyReport(from, rows.size(), outliers.size(),
                outliers.stream().limit(50).toList(), dimensionStats);
    }

    /** 一维分布（中位数 + MAD） */
    private record Dist(double median, double mad) {
    }

    private Dist distOf(List<Map<String, Object>> rows, String col) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal v = toBd(row.get(col));
            if (v != null) {
                values.add(v.doubleValue());
            }
        }
        if (values.size() < 8) {
            return new Dist(0, 0); // 样本太少：不判定（避免小样本误报）
        }
        values.sort(Double::compareTo);
        double median = values.get(values.size() / 2);
        List<Double> deviations = new ArrayList<>(values.size());
        for (double v : values) {
            deviations.add(Math.abs(v - median));
        }
        deviations.sort(Double::compareTo);
        double mad = deviations.get(deviations.size() / 2);
        // MAD=0（大量重复值）时退化用标准差，仍为 0 则置极小值避免除零
        if (mad == 0) {
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double sd = Math.sqrt(values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0));
            mad = sd == 0 ? 1e-4 : sd;
        }
        return new Dist(median, mad);
    }

    /** 稳健 Z 分数：0.6745 × (x − median) / MAD（正态假设下与标准 Z 同尺度） */
    private static double z(Dist dist, Object value) {
        BigDecimal v = toBd(value);
        if (v == null || dist.mad() == 0) {
            return 0;
        }
        return 0.6745 * (v.doubleValue() - dist.median()) / dist.mad();
    }

    private static BigDecimal toBd(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }
}
