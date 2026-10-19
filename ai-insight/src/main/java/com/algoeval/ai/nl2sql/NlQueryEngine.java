package com.algoeval.ai.nl2sql;

import com.algoeval.ai.llm.LlmClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话式查数引擎（NL2SQL，V3 方向三 5.2）。
 * <p>
 * 双引擎策略：
 * <ul>
 *   <li>本地规则引擎（默认）：意图识别（实体/指标/维度/时间）→ 参数化 SQL 模板——
 *       schema 固定可控，规则解析准确率即够用，且零依赖可跑</li>
 *   <li>LLM 引擎（可选）：接入私有 LLM 后由其生成 SQL——但任何 SQL 必须过
 *       {@link SqlGuard} 白名单守卫，守卫拒绝则回退本地引擎（fail closed）</li>
 * </ul>
 */
@Service
public class NlQueryEngine {

    /** 指标定义：关键词 → 列/排序方向/标签（"asc" = 越小越好） */
    private record Metric(String column, String label, String order) {
    }

    private static final List<Map.Entry<String, Metric>> METRICS = List.of(
            Map.entry("vwap", new Metric("vwap_perf_bps", "VWAP绩效(bps)", "asc")),
            Map.entry("twap", new Metric("twap_perf_bps", "TWAP绩效(bps)", "asc")),
            Map.entry("arrival", new Metric("arrival_perf_bps", "Arrival绩效(bps)", "asc")),
            Map.entry("冲击", new Metric("impact_bps", "市场冲击(bps)", "asc")),
            Map.entry("撤单", new Metric("cancel_ratio", "撤单率", "asc")),
            Map.entry("完成", new Metric("fill_ratio", "完成率", "desc")),
            Map.entry("市占率", new Metric("participation", "市占率", "desc")),
            Map.entry("参与率", new Metric("participation", "市占率", "desc")),
            Map.entry("时长", new Metric("exec_minutes", "执行时长(分钟)", "asc")),
            Map.entry("得分", new Metric("total_score", "综合得分", "desc")),
            Map.entry("评分", new Metric("total_score", "综合得分", "desc")));

    private static final Pattern DAYS = Pattern.compile("(?:近|最近)(\\d+)(?:天|日)");
    private static final Pattern MONTHS = Pattern.compile("(?:近|最近)(\\d+)个?月");

    private final JdbcTemplate jdbcTemplate;
    private final SqlGuard sqlGuard;
    private final LlmClient llmClient;

    public NlQueryEngine(JdbcTemplate jdbcTemplate, SqlGuard sqlGuard, LlmClient llmClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlGuard = sqlGuard;
        this.llmClient = llmClient;
    }

    /** 查询结果 */
    public record NlResult(String question, Map<String, Object> understood, String sql, String sqlSource,
                           List<String> columns, List<Map<String, Object>> rows, String chartType,
                           String chartTitle) {
    }

    /**
     * 自然语言查数主入口：本地解析 → SQL → 守卫 → 执行。
     * LLM 可用时优先 LLM 生成（守卫校验，失败回退本地）。
     */
    public NlResult ask(String question) {
        Understanding u = parse(question);
        String sql = null;
        String source = "llm";

        if (llmClient.available()) {
            String llmSql = llmSql(u, question);
            if (llmSql != null) {
                SqlGuard.Verdict verdict = sqlGuard.check(llmSql);
                if (verdict.allowed()) {
                    sql = verdict.sql();
                }
            }
        }
        if (sql == null) {
            sql = localSql(u);
            source = "rule";
        }

        SqlGuard.Verdict verdict = sqlGuard.check(sql);
        if (!verdict.allowed()) {
            throw new IllegalArgumentException("生成的 SQL 未通过白名单守卫：" + verdict.rejection());
        }
        sql = verdict.sql();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        List<String> columns = rows.isEmpty()
                ? List.of()
                : List.copyOf(rows.get(0).keySet());
        return new NlResult(question, u.toMap(), sql, source, columns, rows,
                u.intent().equals("trend") ? "line" : "bar",
                u.metric() == null ? "查询结果" : u.metric().label() + " · " + labelOf(u.intent()));
    }

    /** 解析出的查询语义 */
    record Understanding(String intent, String algoInstance, Metric metric, LocalDate from, LocalDate to) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent", intent);
            m.put("intentLabel", labelOf(intent));
            m.put("algoInstance", algoInstance);
            m.put("metric", metric == null ? null : metric.label());
            m.put("from", from.toString());
            m.put("to", to.toString());
            return m;
        }
    }

    private static String labelOf(String intent) {
        return switch (intent) {
            case "trend" -> "每日走势";
            case "vendor" -> "供应商对比";
            case "bucket" -> "市占率分桶";
            default -> "算法对比排名";
        };
    }

    /** 规则解析：意图 → 实体 → 指标 → 时间窗 */
    Understanding parse(String question) {
        String q = question == null ? "" : question.trim();
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        Matcher days = DAYS.matcher(q);
        Matcher months = MONTHS.matcher(q);
        boolean daysMatched = days.find();
        boolean monthsMatched = !daysMatched && months.find();
        if (daysMatched) {
            from = to.minusDays(Integer.parseInt(days.group(1)));
        } else if (monthsMatched) {
            from = to.minusDays(Integer.parseInt(months.group(1)) * 30L);
        } else if (q.contains("今天") || q.contains("今日")) {
            from = to;
        }

        // 实体：已知算法实例
        String algo = null;
        for (String instance : jdbcTemplate.queryForList(
                "SELECT DISTINCT algo_instance FROM algo_daily_summary", String.class)) {
            if (q.toLowerCase().contains(instance.toLowerCase())) {
                algo = instance;
                break;
            }
        }

        // 指标
        Metric metric = null;
        String lower = q.toLowerCase();
        for (Map.Entry<String, Metric> e : METRICS) {
            if (lower.contains(e.getKey())) {
                metric = e.getValue();
                break;
            }
        }
        if (metric == null) {
            metric = new Metric("vwap_perf_bps", "VWAP绩效(bps)", "asc"); // 默认绩效
        }

        // 意图
        String intent;
        if (q.contains("供应商") || q.contains("KAFANG") || q.contains("TOPLUS")
                || q.contains("厂商") || q.contains("vendor")) {
            intent = "vendor";
        } else if (q.contains("走势") || q.contains("趋势") || q.contains("变化")
                || q.contains("每日") || (algo != null && (daysMatched || monthsMatched || q.contains("近")))) {
            intent = "trend";
        } else if (q.contains("分桶") || q.contains("区间") || (q.contains("市占率") && q.contains("绩效"))) {
            intent = "bucket";
        } else {
            intent = "rank";
        }
        return new Understanding(intent, algo, metric, from, to);
    }

    /** 本地规则引擎：意图 → 参数化 SQL 模板（val 避免 H2 保留字 value） */
    String localSql(Understanding u) {
        String inFrom = "'" + u.from() + "'";
        String inTo = "'" + u.to() + "'";
        // 综合得分只存在于 algo_daily_summary（无供应商/市占率维度）：降级为算法排名口径
        boolean scoreMetric = "total_score".equals(u.metric().column());
        String intent = scoreMetric && ("vendor".equals(u.intent()) || "bucket".equals(u.intent()))
                ? "rank" : u.intent();
        return switch (intent) {
            case "trend" -> {
                if (scoreMetric) {
                    yield String.format(
                            "SELECT trade_date, ROUND(AVG(%s),4) AS val FROM algo_daily_summary "
                                    + "WHERE trade_date BETWEEN %s AND %s %s GROUP BY trade_date ORDER BY trade_date",
                            u.metric().column(), inFrom, inTo,
                            u.algoInstance() == null ? "" : "AND algo_instance='" + u.algoInstance() + "'");
                }
                yield String.format(
                        "SELECT r.trade_date, ROUND(AVG(r.%s),4) AS val FROM indicator_result r "
                                + "JOIN parent_order p ON r.order_id=p.order_id AND r.trade_date=p.trade_date "
                                + "WHERE r.trade_date BETWEEN %s AND %s %s GROUP BY r.trade_date ORDER BY r.trade_date",
                        u.metric().column(), inFrom, inTo,
                        u.algoInstance() == null ? "" : "AND r.algo_instance='" + u.algoInstance() + "'");
            }
            case "vendor" -> String.format(
                    "SELECT p.algo_vendor AS dim, ROUND(AVG(r.%s),4) AS val FROM indicator_result r "
                            + "JOIN parent_order p ON r.order_id=p.order_id AND r.trade_date=p.trade_date "
                            + "WHERE r.trade_date BETWEEN %s AND %s GROUP BY p.algo_vendor ORDER BY val %s",
                    u.metric().column(), inFrom, inTo, u.metric().order());
            case "bucket" -> String.format(
                    "SELECT CASE WHEN r.participation<0.0005 THEN '[0,0.05%%)' "
                            + "WHEN r.participation<0.002 THEN '[0.05,0.2%%)' ELSE '[0.2,100]' END AS dim, "
                            + "ROUND(AVG(r.%s),4) AS val, COUNT(*) AS cnt FROM indicator_result r "
                            + "WHERE r.trade_date BETWEEN %s AND %s %s GROUP BY dim ORDER BY dim",
                    u.metric().column(), inFrom, inTo,
                    u.algoInstance() == null ? "" : "AND r.algo_instance='" + u.algoInstance() + "'");
            default -> scoreMetric ? String.format(
                    "SELECT algo_instance AS dim, ROUND(AVG(%s),4) AS val, SUM(order_count) AS cnt "
                            + "FROM algo_daily_summary WHERE trade_date BETWEEN %s AND %s "
                            + "GROUP BY algo_instance ORDER BY val %s",
                    u.metric().column(), inFrom, inTo, u.metric().order())
                    : String.format(
                    "SELECT r.algo_instance AS dim, ROUND(AVG(r.%s),4) AS val, COUNT(*) AS cnt "
                            + "FROM indicator_result r JOIN parent_order p "
                            + "ON r.order_id=p.order_id AND r.trade_date=p.trade_date "
                            + "WHERE r.trade_date BETWEEN %s AND %s GROUP BY r.algo_instance ORDER BY val %s",
                    u.metric().column(), inFrom, inTo, u.metric().order());
        };
    }

    /** LLM 生成 SQL（schema + 白名单规则注入提示词；温度压低） */
    private String llmSql(Understanding u, String question) {
        String system = "你是 SQL 生成器。只输出一条 SELECT 语句，不要解释，不要分号结尾。规则："
                + "只允许查询表 " + sqlGuard.whitelistedTables() + "；"
                + "指标列在 indicator_result（vwap_perf_bps/twap_perf_bps/arrival_perf_bps/cancel_ratio/"
                + "fill_ratio/participation/impact_bps/exec_minutes）；综合得分在 algo_daily_summary.total_score；"
                + "母单属性（algo_vendor/symbol/side/client_name）在 parent_order，"
                + "与 indicator_result 用 order_id+trade_date 关联；"
                + "绩效 bps/撤单率/冲击越小越好，完成率/得分越大越好；禁止注释与子查询 DML。";
        String user = "问题：" + question + "\n参考解析（本地规则引擎的理解，可能有误，请校正）："
                + u.toMap();
        return llmClient.complete(system, user);
    }

    /** 示例问题（前端引导） */
    public List<String> examples() {
        return List.of(
                "各算法 VWAP 绩效对比",
                "kf_vwap_plus 近30天 VWAP 绩效走势",
                "供应商撤单率对比",
                "kf_t0 市占率分桶绩效",
                "各算法综合得分排名");
    }
}
