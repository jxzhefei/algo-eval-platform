package com.algoeval.statistic;

import com.algoeval.infra.cache.TwoLevelCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计分析 API（页面体系对齐产品截图实证）：
 * 总览（指标卡 + 算法/T0 双卡片 + 三维度排名）/ 算法概览（实例+算法商）/
 * 供应商对比 / 五维评分（叠加行业均值 + 跑赢跑平跑输）/ 对比实例。
 * 热点查询走 Caffeine+Redis 二级缓存（§6.6）。
 */
@RestController
@RequestMapping("/api/stat")
public class StatisticController {

    private final JdbcTemplate jdbcTemplate;
    private final TwoLevelCache cache;

    public StatisticController(JdbcTemplate jdbcTemplate, TwoLevelCache cache) {
        this.jdbcTemplate = jdbcTemplate;
        this.cache = cache;
    }

    /** 总览：指标卡 + 「算法总览 / T0 总览」双卡片 + 三维度绩效排名（wx_06 实证形态） */
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) String date) {
        LocalDate d = parseDate(date);
        return cache.get("stat:overview:" + d, Map.class,
                () -> buildOverview(d));
    }

    private Map<String, Object> buildOverview(LocalDate d) {
        Date date = Date.valueOf(d);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", d.toString());

        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("turnover", nvl(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(filled_amount),0) FROM parent_order WHERE trade_date=?",
                java.math.BigDecimal.class, date)));
        cards.put("symbolCount", count("SELECT COUNT(DISTINCT symbol) FROM parent_order WHERE trade_date=?", date));
        cards.put("parentOrders", count("SELECT COUNT(*) FROM parent_order WHERE trade_date=?", date));
        cards.put("childOrders", count(
                "SELECT COUNT(*) FROM child_order c JOIN parent_order p ON c.order_id=p.order_id "
                        + "AND c.trade_date=p.trade_date WHERE p.trade_date=?", date));
        cards.put("algoCount", count("SELECT COUNT(DISTINCT algo_instance) FROM parent_order WHERE trade_date=?", date));
        cards.put("vendorCount", count("SELECT COUNT(DISTINCT algo_vendor) FROM parent_order WHERE trade_date=?", date));
        cards.put("clientCount", count("SELECT COUNT(DISTINCT client_name) FROM parent_order WHERE trade_date=?", date));
        cards.put("accountCount", count("SELECT COUNT(DISTINCT account_id) FROM parent_order WHERE trade_date=?", date));
        result.put("cards", cards);

        // 算法总览（非 T0 轨）
        Map<String, Object> algo = jdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(p.task_amount),0) AS task_amount, "
                        + "COALESCE(SUM(p.filled_amount),0) AS filled_amount, "
                        + "COUNT(*) AS parent_count, "
                        + "(SELECT COUNT(*) FROM child_order c JOIN parent_order p2 ON c.order_id=p2.order_id "
                        + "AND c.trade_date=p2.trade_date WHERE p2.trade_date=? AND p2.algo_type<>'T0') AS child_count, "
                        + "(SELECT AVG(r.twap_perf_bps) FROM indicator_result r JOIN parent_order p3 "
                        + "ON r.order_id=p3.order_id AND r.trade_date=p3.trade_date "
                        + "WHERE r.trade_date=? AND p3.algo_type<>'T0') AS twap_bps, "
                        + "(SELECT AVG(r.vwap_perf_bps) FROM indicator_result r JOIN parent_order p4 "
                        + "ON r.order_id=p4.order_id AND r.trade_date=p4.trade_date "
                        + "WHERE r.trade_date=? AND p4.algo_type<>'T0') AS vwap_bps "
                        + "FROM parent_order p WHERE p.trade_date=? AND p.algo_type<>'T0'",
                date, date, date, date);
        algo.put("taskFillRatio", ratio(algo.get("filled_amount"), algo.get("task_amount")));
        result.put("algoOverview", algo);

        // T0 总览（T0 轨：开仓金额/净收益/收益率等来自 to_day_detail 与明细）
        Map<String, Object> t0 = jdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(p.task_amount),0) AS task_amount, COUNT(*) AS parent_count, "
                        + "(SELECT COUNT(*) FROM child_order c JOIN parent_order p2 ON c.order_id=p2.order_id "
                        + "AND c.trade_date=p2.trade_date WHERE p2.trade_date=? AND p2.algo_type='T0') AS child_count, "
                        + "(SELECT COALESCE(SUM(d.open_amount),0) FROM to_day_detail d WHERE d.trade_date=?) AS open_amount, "
                        + "(SELECT COALESCE(SUM(d.net_profit),0) FROM to_day_detail d WHERE d.trade_date=?) AS net_profit, "
                        + "(SELECT AVG(d.day_return_bps) FROM to_day_detail d WHERE d.trade_date=?) AS day_return_bps "
                        + "FROM parent_order p WHERE p.trade_date=? AND p.algo_type='T0'",
                date, date, date, date, date);
        t0.put("openRatio", ratio(t0.get("open_amount"), t0.get("task_amount")));
        result.put("t0Overview", t0);

        // 三维度绩效排名（算法实例 / 机构名称 / 证券名称，wx_06 实证）
        result.put("rankByAlgo", rank(d, "algo"));
        result.put("rankByClient", rank(d, "client"));
        result.put("rankBySymbol", rank(d, "symbol"));
        return result;
    }

    /** 绩效排名（三维度：algo=算法实例 / client=机构名称 / symbol=证券名称） */
    private List<Map<String, Object>> rank(LocalDate d, String dim) {
        String groupCol = switch (dim) {
            case "client" -> "p.client_name";
            case "symbol" -> "p.symbol";
            default -> "r.algo_instance";
        };
        return jdbcTemplate.queryForList(
                "SELECT " + groupCol + " AS name, COUNT(*) AS cnt, AVG(r.vwap_perf_bps) AS vwap_bps, "
                        + "AVG(r.twap_perf_bps) AS twap_bps, AVG(r.arrival_perf_bps) AS arrival_bps "
                        + "FROM indicator_result r JOIN parent_order p ON r.order_id=p.order_id "
                        + "AND r.trade_date=p.trade_date "
                        + "WHERE r.trade_date=? AND p.algo_type<>'T0' GROUP BY " + groupCol
                        + " ORDER BY ABS(AVG(r.vwap_perf_bps)) ASC",
                Date.valueOf(d));
    }

    /** 算法排名（按综合得分或绩效偏离，沿用） */
    @GetMapping("/algo-rank")
    public List<Map<String, Object>> algoRank(@RequestParam(required = false) String date,
                                              @RequestParam(defaultValue = "total_score") String by) {
        LocalDate d = parseDate(date);
        String orderCol = "total_score".equals(by) ? "total_score DESC" : "ABS(avg_vwap_bps) ASC";
        return cache.get("stat:algorank:" + d + ":" + by, List.class, () ->
                jdbcTemplate.queryForList(
                        "SELECT algo_instance, order_count, avg_vwap_bps, avg_twap_bps, avg_cancel_ratio, "
                                + "avg_fill_ratio, perf_score, fill_score, stability_score, risk_score, "
                                + "impact_score, total_score FROM algo_daily_summary WHERE trade_date=? "
                                + "ORDER BY " + orderCol,
                        Date.valueOf(d)));
    }

    /** 供应商（算法商）对比：单日各算法商指标均值 */
    @GetMapping("/vendor-compare")
    public List<Map<String, Object>> vendorCompare(@RequestParam(required = false) String date) {
        LocalDate d = parseDate(date);
        return cache.get("stat:vendorcmp:" + d, List.class, () ->
                jdbcTemplate.queryForList(
                        "SELECT p.algo_vendor, COUNT(*) AS order_count, AVG(r.vwap_perf_bps) AS avg_vwap_bps, "
                                + "AVG(r.twap_perf_bps) AS avg_twap_bps, AVG(r.cancel_ratio) AS avg_cancel_ratio, "
                                + "AVG(r.reject_ratio) AS avg_reject_ratio, AVG(r.fill_ratio) AS avg_fill_ratio, "
                                + "AVG(r.impact_bps) AS avg_impact_bps "
                                + "FROM indicator_result r JOIN parent_order p ON r.order_id=p.order_id "
                                + "AND r.trade_date=p.trade_date WHERE r.trade_date=? GROUP BY p.algo_vendor "
                                + "ORDER BY p.algo_vendor",
                        Date.valueOf(d)));
    }

    /** 五维评分明细（雷达图数据）：叠加行业均值 + 逐维度"跑赢/跑平/跑输"判定（wx_04 实证） */
    @GetMapping("/score")
    public Map<String, Object> score(@RequestParam(required = false) String date) {
        LocalDate d = parseDate(date);
        return cache.get("stat:score:" + d, Map.class, () -> {
            List<Map<String, Object>> instances = jdbcTemplate.queryForList(
                    "SELECT algo_instance, perf_score, fill_score, stability_score, risk_score, impact_score, "
                            + "total_score FROM algo_daily_summary WHERE trade_date=? ORDER BY total_score DESC",
                    Date.valueOf(d));
            Map<String, Object> baseline = new LinkedHashMap<>(jdbcTemplate.queryForMap(
                    "SELECT COALESCE(MAX(CASE WHEN dimension='PERF' THEN industry_avg END),0) AS perf_score, "
                            + "COALESCE(MAX(CASE WHEN dimension='FILL' THEN industry_avg END),0) AS fill_score, "
                            + "COALESCE(MAX(CASE WHEN dimension='STABILITY' THEN industry_avg END),0) AS stability_score, "
                            + "COALESCE(MAX(CASE WHEN dimension='RISK' THEN industry_avg END),0) AS risk_score, "
                            + "COALESCE(MAX(CASE WHEN dimension='IMPACT' THEN industry_avg END),0) AS impact_score, "
                            + "COALESCE(MAX(CASE WHEN dimension='TOTAL' THEN industry_avg END),0) AS total_score "
                            + "FROM industry_baseline WHERE trade_date=?",
                    Date.valueOf(d)));
            for (Map<String, Object> inst : instances) {
                inst.put("vsIndustry", Map.of(
                        "perf", verdict(inst.get("perf_score"), baseline.get("perf_score")),
                        "fill", verdict(inst.get("fill_score"), baseline.get("fill_score")),
                        "stability", verdict(inst.get("stability_score"), baseline.get("stability_score")),
                        "risk", verdict(inst.get("risk_score"), baseline.get("risk_score")),
                        "impact", verdict(inst.get("impact_score"), baseline.get("impact_score")),
                        "total", verdict(inst.get("total_score"), baseline.get("total_score"))));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instances", instances);
            result.put("industryBaseline", baseline);
            result.put("date", d.toString());
            return result;
        });
    }

    /** 对比实例（wx_04 实证）：五维评分对比 + 行业均值叠加 + 三基准绩效走势双线 */
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam String left,
                                       @RequestParam String right,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to) {
        LocalDate toDate = to == null ? LocalDate.now() : LocalDate.parse(to);
        LocalDate fromDate = from == null ? toDate.minusDays(90) : LocalDate.parse(from);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("left", scoreAvg(left, fromDate, toDate));
        result.put("right", scoreAvg(right, fromDate, toDate));
        result.put("industryBaseline", baselineAvg(fromDate, toDate));
        result.put("trend", Map.of(
                "dates", jdbcTemplate.queryForList(
                        "SELECT DISTINCT trade_date FROM algo_daily_summary WHERE algo_instance IN (?,?) "
                                + "AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
                        java.sql.Date.class, left, right, Date.valueOf(fromDate), Date.valueOf(toDate)),
                left, trendOf(left, fromDate, toDate),
                right, trendOf(right, fromDate, toDate)));
        return result;
    }

    private Map<String, Object> scoreAvg(String instance, LocalDate from, LocalDate to) {
        return jdbcTemplate.queryForMap(
                "SELECT algo_instance, AVG(perf_score) AS perf_score, AVG(fill_score) AS fill_score, "
                        + "AVG(stability_score) AS stability_score, AVG(risk_score) AS risk_score, "
                        + "AVG(impact_score) AS impact_score, AVG(total_score) AS total_score "
                        + "FROM algo_daily_summary WHERE algo_instance=? AND trade_date BETWEEN ? AND ?",
                instance, Date.valueOf(from), Date.valueOf(to));
    }

    private Map<String, Object> baselineAvg(LocalDate from, LocalDate to) {
        return jdbcTemplate.queryForMap(
                "SELECT AVG(CASE WHEN dimension='PERF' THEN industry_avg END) AS perf_score, "
                        + "AVG(CASE WHEN dimension='FILL' THEN industry_avg END) AS fill_score, "
                        + "AVG(CASE WHEN dimension='STABILITY' THEN industry_avg END) AS stability_score, "
                        + "AVG(CASE WHEN dimension='RISK' THEN industry_avg END) AS risk_score, "
                        + "AVG(CASE WHEN dimension='IMPACT' THEN industry_avg END) AS impact_score, "
                        + "AVG(CASE WHEN dimension='TOTAL' THEN industry_avg END) AS total_score "
                        + "FROM industry_baseline WHERE trade_date BETWEEN ? AND ?",
                Date.valueOf(from), Date.valueOf(to));
    }

    private List<Map<String, Object>> trendOf(String instance, LocalDate from, LocalDate to) {
        return jdbcTemplate.queryForList(
                "SELECT trade_date, avg_vwap_bps, avg_twap_bps FROM algo_daily_summary "
                        + "WHERE algo_instance=? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
                instance, Date.valueOf(from), Date.valueOf(to));
    }

    private static String verdict(Object score, Object baseline) {
        if (score == null || baseline == null) {
            return "N/A";
        }
        double s = ((Number) score).doubleValue();
        double b = ((Number) baseline).doubleValue();
        if (b == 0 || Math.abs(s - b) / b <= 0.02) {
            return "持平";
        }
        return s > b ? "跑赢行业" : "跑输行业";
    }

    private static Object nvl(Object v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }

    private static Object ratio(Object part, Object total) {
        if (part == null || total == null) {
            return java.math.BigDecimal.ZERO;
        }
        java.math.BigDecimal p = toDecimal(part);
        java.math.BigDecimal t = toDecimal(total);
        if (t.signum() == 0) {
            return java.math.BigDecimal.ZERO;
        }
        return p.divide(t, 4, java.math.RoundingMode.HALF_UP);
    }

    private static java.math.BigDecimal toDecimal(Object v) {
        return v instanceof java.math.BigDecimal b ? b : java.math.BigDecimal.valueOf(((Number) v).doubleValue());
    }

    private long count(String sql, Date date) {
        Long c = jdbcTemplate.queryForObject(sql, Long.class, date);
        return c == null ? 0 : c;
    }

    private static LocalDate parseDate(String date) {
        return date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
    }
}
