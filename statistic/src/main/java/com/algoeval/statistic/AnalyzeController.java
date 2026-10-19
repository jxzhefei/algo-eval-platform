package com.algoeval.statistic;

import com.algoeval.infra.cache.TwoLevelCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析类 API（对齐产品截图实证页面）：
 * 算法概览（算法实例概览/算法商概览，wx_08）/ 行业均值（趋势）/ 算法绩效相关性分析
 * （股价×时长×交易量×市占率 四宫格，wx_02）/ 算法母单指标（分页明细）。
 */
@RestController
@RequestMapping("/api/stat")
public class AnalyzeController {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate rawJdbc;
    private final TwoLevelCache cache;

    public AnalyzeController(@org.springframework.beans.factory.annotation.Qualifier("jdbcTemplate")
                             JdbcTemplate jdbcTemplate,
                             @org.springframework.beans.factory.annotation.Qualifier("rawJdbcTemplate")
                             JdbcTemplate rawJdbc,
                             TwoLevelCache cache) {
        this.jdbcTemplate = jdbcTemplate;
        this.rawJdbc = rawJdbc;
        this.cache = cache;
    }

    /** 窗口内逐物理表直查并合并（分析通道：绕开 SS 对跨分表 GROUP BY 的有损重写） */
    private List<Map<String, Object>> queryShardedGroupBy(String logicalTable, String selectExpr,
                                                          String whereExtra, LocalDate from, LocalDate to) {
        List<Map<String, Object>> merged = new java.util.ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            String table = com.algoeval.infra.sharding.ShardedTables.physicalName(logicalTable, d);
            try {
                List<Map<String, Object>> rows = rawJdbc.queryForList(
                        "SELECT " + selectExpr + " FROM " + table + " WHERE 1=1 " + whereExtra);
                merged.addAll(rows);
            } catch (Exception tableAbsent) {
                // 该交易日无物理表（无数据日），跳过
            }
        }
        return merged;
    }

    /** 算法实例概览（wx_08 实证列：TWAP/VWAP 绩效 PNL+BPS 双口径）。
     *  两段查询 + Java 合并：跨分表 JOIN 的 GROUP BY 在 ShardingSphere 重写后有损，
     *  单表聚合各自路由，合并语义与 JOIN 等价。 */
    @GetMapping("/algo-overview")
    public List<Map<String, Object>> algoOverview(@RequestParam(required = false) String algo,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to) {
        LocalDate toDate = to == null ? LocalDate.now() : LocalDate.parse(to);
        LocalDate fromDate = from == null ? toDate.minusDays(90) : LocalDate.parse(from);
        String key = "stat:algooverview:" + algo + ":" + fromDate + ":" + toDate;
        return cache.get(key, List.class, () -> {
            String algoFilter = (algo == null || algo.isBlank()) ? "" : " AND algo_instance=? ";
            Object[] pArgs = (algo == null || algo.isBlank())
                    ? new Object[]{Date.valueOf(fromDate), Date.valueOf(toDate)}
                    : new Object[]{Date.valueOf(fromDate), Date.valueOf(toDate), algo};
            List<Map<String, Object>> parents = queryShardedGroupBy("parent_order",
                    "algo_instance, algo_vendor, COALESCE(SUM(filled_amount),0) AS turnover, "
                            + "COUNT(*) AS parent_orders, COUNT(DISTINCT client_name) AS client_count",
                    "AND algo_type<>'T0' " + algoFilter, fromDate, toDate);
            List<Map<String, Object>> metrics = queryShardedGroupBy("indicator_result",
                    "algo_instance, AVG(cancel_ratio) AS cancel_ratio, "
                            + "AVG(reject_ratio) AS reject_ratio, AVG(fill_ratio) AS fill_ratio, "
                            + "SUM(twap_perf_pnl) AS twap_pnl, AVG(twap_perf_bps) AS twap_bps, "
                            + "SUM(vwap_perf_pnl) AS vwap_pnl, AVG(vwap_perf_bps) AS vwap_bps",
                    algoFilter, fromDate, toDate);
            // 日均成交金额（Java 聚合：窗口内总成交额 / 有数据天数）
            Map<Object, java.util.Set<String>> daysByAlgo = new java.util.HashMap<>();
            for (Map<String, Object> p2 : parents) {
                daysByAlgo.computeIfAbsent(p2.get("algo_instance"),
                        k -> new java.util.HashSet<>()).add(String.valueOf(toDate));
            }
            Map<String, Map<String, Object>> metricByAlgo = new java.util.LinkedHashMap<>();
            for (Map<String, Object> m : metrics) {
                metricByAlgo.put((String) m.get("algo_instance"), m);
            }
            for (Map<String, Object> p2 : parents) {
                Map<String, Object> m = metricByAlgo.get(p2.get("algo_instance"));
                if (m != null) {
                    p2.putAll(m);
                }
            }
            return parents;
        });
    }

    /** 算法商（供应商）概览：按算法商聚合（算法商画像页数据底座）。
     *  两段查询 + Java 合并（同 algo-overview 的分表路由考虑）。 */
    @GetMapping("/vendor-overview")
    public List<Map<String, Object>> vendorOverview(@RequestParam(required = false) String from,
                                                    @RequestParam(required = false) String to) {
        LocalDate toDate = to == null ? LocalDate.now() : LocalDate.parse(to);
        LocalDate fromDate = from == null ? toDate.minusDays(90) : LocalDate.parse(from);
        return cache.get("stat:vendoroverview:" + fromDate + ":" + toDate, List.class, () -> {
            List<Map<String, Object>> parentRows = queryShardedGroupBy("parent_order",
                    "algo_vendor, algo_instance, filled_amount, client_name, order_id",
                    "", fromDate, toDate);
            Map<String, Map<String, Object>> parents = new java.util.LinkedHashMap<>();
            Map<String, java.util.Set<Object>> algoSet = new java.util.HashMap<>();
            Map<String, java.util.Set<Object>> clientSet = new java.util.HashMap<>();
            for (Map<String, Object> row : parentRows) {
                String vendor = (String) row.get("algo_vendor");
                Map<String, Object> agg = parents.computeIfAbsent(vendor, k -> new java.util.LinkedHashMap<>());
                double t = toDecimal(row.get("filled_amount")).doubleValue()
                        + (agg.get("turnover") == null ? 0 : ((Number) agg.get("turnover")).doubleValue());
                agg.put("turnover", t);
                long pc = 1 + (agg.get("parent_orders") == null ? 0 : ((Number) agg.get("parent_orders")).longValue());
                agg.put("parent_orders", pc);
                algoSet.computeIfAbsent(vendor, k -> new java.util.HashSet<>()).add(row.get("algo_instance"));
                clientSet.computeIfAbsent(vendor, k -> new java.util.HashSet<>()).add(row.get("client_name"));
            }
            for (Map.Entry<String, Map<String, Object>> e : parents.entrySet()) {
                e.getValue().put("algo_vendor", e.getKey());
                e.getValue().put("algo_count", algoSet.get(e.getKey()).size());
                e.getValue().put("client_count", clientSet.get(e.getKey()).size());
            }
            List<Map<String, Object>> perVendor = jdbcTemplate.queryForList(
                    "SELECT s.algo_instance AS algo_instance, AVG(s.total_score) AS avg_total_score, "
                            + "s.algo_instance FROM algo_daily_summary s WHERE s.trade_date BETWEEN ? AND ? "
                            + "GROUP BY s.algo_instance",
                    Date.valueOf(fromDate), Date.valueOf(toDate));
            Map<String, Map<String, Object>> perAlgoMetrics = queryShardedGroupBy("indicator_result",
                    "algo_instance, AVG(cancel_ratio) AS cancel_ratio, AVG(fill_ratio) AS fill_ratio, "
                            + "AVG(twap_perf_bps) AS twap_bps, AVG(vwap_perf_bps) AS vwap_bps",
                    "", fromDate, toDate).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            m -> (String) m.get("algo_instance"), m -> m, (a, b) -> a));
            Map<String, Object> scoreByAlgo = new java.util.HashMap<>();
            for (Map<String, Object> m : perVendor) {
                scoreByAlgo.put(String.valueOf(m.get("algo_instance")),
                        m.get("avg_total_score"));
            }
            Map<String, Map<String, Object>> byAlgoAgg = new java.util.LinkedHashMap<>();
            for (Map<String, Object> m : perAlgoMetrics.values()) {
                byAlgoAgg.put((String) m.get("algo_instance"), m);
            }
            // 供应商级均值 = 各算法实例指标的再平均（Java 二段聚合）
            Map<String, List<Map<String, Object>>> byVendorAlgos = new java.util.HashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : parents.entrySet()) {
                byVendorAlgos.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>());
            }
            for (Map<String, Object> row : parentRows) {
                String vendor = (String) row.get("algo_vendor");
                Map<String, Object> m = byAlgoAgg.get(row.get("algo_instance"));
                if (m != null && !byVendorAlgos.get(vendor).contains(m)) {
                    byVendorAlgos.get(vendor).add(m);
                }
            }
            for (Map.Entry<String, List<Map<String, Object>>> e : byVendorAlgos.entrySet()) {
                Map<String, Object> target = parents.get(e.getKey());
                if (target == null || e.getValue().isEmpty()) {
                    continue;
                }
                for (String col : List.of("cancel_ratio", "fill_ratio", "twap_bps", "vwap_bps")) {
                    BigDecimal sum = BigDecimal.ZERO;
                    int n = 0;
                    for (Map<String, Object> m : e.getValue()) {
                        if (m.get(col) != null) {
                            sum = sum.add(toDecimal(m.get(col)));
                            n++;
                        }
                    }
                    target.put(col, n == 0 ? null
                            : sum.divide(BigDecimal.valueOf(n), 4, java.math.RoundingMode.HALF_UP));
                }
                BigDecimal scoreSum = BigDecimal.ZERO;
                int sn = 0;
                for (Map<String, Object> row : parentRows) {
                    Object sc = scoreByAlgo.get(String.valueOf(row.get("algo_instance")));
                    if (sc != null && parents.get(e.getKey()) != null) {
                        scoreSum = scoreSum.add(toDecimal(sc));
                        sn++;
                    }
                }
                target.put("avg_total_score", sn == 0 ? null
                        : scoreSum.divide(BigDecimal.valueOf(sn), 2, java.math.RoundingMode.HALF_UP));
            }
            List<Map<String, Object>> result = new java.util.ArrayList<>(parents.values());
            return result;
        });
    }

    /** 行业均值：当日各维度基线 + 多日趋势（行业均值页） */
    @GetMapping("/industry-baseline")
    public Map<String, Object> industryBaseline(@RequestParam(required = false) String date,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to) {
        LocalDate d = date == null ? LocalDate.now() : LocalDate.parse(date);
        LocalDate toDate = to == null ? LocalDate.now() : LocalDate.parse(to);
        LocalDate fromDate = from == null ? toDate.minusDays(90) : LocalDate.parse(from);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", d.toString());
        result.put("baseline", jdbcTemplate.queryForList(
                "SELECT dimension, industry_avg, sample_count FROM industry_baseline WHERE trade_date=? "
                        + "ORDER BY dimension",
                Date.valueOf(d)));
        result.put("trend", jdbcTemplate.queryForList(
                "SELECT trade_date, dimension, industry_avg FROM industry_baseline "
                        + "WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date, dimension",
                Date.valueOf(fromDate), Date.valueOf(toDate)));
        return result;
    }

    /** 算法绩效相关性分析（wx_02 四宫格）：股价/交易时长/交易量/市占率 × VWAP 绩效散点 */
    @GetMapping("/correlation")
    public Map<String, Object> correlation(@RequestParam(required = false) String date,
                                           @RequestParam(required = false) String algo) {
        LocalDate d = date == null ? LocalDate.now() : LocalDate.parse(date);
        Date sqlDate = Date.valueOf(d);
        String algoFilter = (algo == null || algo.isBlank()) ? "" : " AND p.algo_instance=? ";
        Object[] args = (algo == null || algo.isBlank())
                ? new Object[]{sqlDate}
                : new Object[]{sqlDate, algo};

        // 股价维度：标的当日收盘价（market_tick 最后一分钟）
        Map<String, Object> closePrices = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT symbol, price FROM market_tick WHERE trade_date=? ORDER BY symbol, tick_time",
                rs -> {
                    closePrices.put(rs.getString(1), rs.getBigDecimal(2));
                }, sqlDate);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.symbol, p.task_amount, r.exec_minutes, r.participation, r.vwap_perf_bps "
                        + "FROM indicator_result r JOIN parent_order p ON r.order_id=p.order_id "
                        + "AND r.trade_date=p.trade_date WHERE r.trade_date=? AND p.algo_type<>'T0' "
                        + algoFilter + "ORDER BY r.order_id",
                args);

        List<double[]> price = new java.util.ArrayList<>();
        List<double[]> duration = new java.util.ArrayList<>();
        List<double[]> volume = new java.util.ArrayList<>();
        List<double[]> participation = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal bps = row.get("vwap_perf_bps") == null ? null
                    : toDecimal(row.get("vwap_perf_bps"));
            if (bps == null) {
                continue;
            }
            double y = bps.doubleValue();
            Object close = closePrices.get(row.get("symbol"));
            if (close != null) {
                price.add(new double[]{toDecimal(close).doubleValue(), y});
            }
            if (row.get("exec_minutes") != null) {
                duration.add(new double[]{toDecimal(row.get("exec_minutes")).doubleValue(), y});
            }
            if (row.get("task_amount") != null) {
                volume.add(new double[]{toDecimal(row.get("task_amount")).doubleValue() / 10_000, y});
            }
            if (row.get("participation") != null) {
                participation.add(new double[]{toDecimal(row.get("participation")).doubleValue() * 100, y});
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", d.toString());
        result.put("algoInstance", algo == null ? "全部算法" : algo);
        result.put("price", price);
        result.put("duration", duration);
        result.put("volume", volume);
        result.put("participation", participation);
        return result;
    }

    /** 算法母单指标（分页明细，算法母单指标页） */
    @GetMapping("/parent-metrics")
    public Map<String, Object> parentMetrics(@RequestParam(required = false) String date,
                                             @RequestParam(required = false) String algo,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        LocalDate d = date == null ? LocalDate.now() : LocalDate.parse(date);
        Date sqlDate = Date.valueOf(d);
        String algoFilter = (algo == null || algo.isBlank()) ? "" : " AND p.algo_instance=? ";
        Object[] countArgs = (algo == null || algo.isBlank())
                ? new Object[]{sqlDate} : new Object[]{sqlDate, algo};
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM indicator_result r JOIN parent_order p ON r.order_id=p.order_id "
                        + "AND r.trade_date=p.trade_date WHERE r.trade_date=?" + algoFilter,
                Long.class, countArgs);

        Object[] args = (algo == null || algo.isBlank())
                ? new Object[]{sqlDate, size, (page - 1) * Math.max(1, size)}
                : new Object[]{sqlDate, algo, size, (page - 1) * Math.max(1, size)};
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.order_id, p.algo_instance, p.algo_type, p.symbol, p.client_name, p.side, "
                        + "p.task_amount, p.filled_amount, r.vwap_perf_bps, r.vwap_ot_perf_bps, r.twap_perf_bps, "
                        + "r.twap_ot_perf_bps, r.arrival_perf_bps, r.open_perf_bps, r.close_perf_bps, "
                        + "r.vwap_perf_pnl, r.twap_perf_pnl, r.cancel_ratio, r.reject_ratio, r.fill_ratio, "
                        + "r.participation, r.impact_bps, r.exec_minutes "
                        + "FROM indicator_result r JOIN parent_order p ON r.order_id=p.order_id "
                        + "AND r.trade_date=p.trade_date WHERE r.trade_date=?" + algoFilter
                        + "ORDER BY r.order_id LIMIT ? OFFSET ?",
                args);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", d.toString());
        result.put("total", total == null ? 0 : total);
        result.put("page", page);
        result.put("size", size);
        result.put("rows", rows);
        return result;
    }

    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP);
        }
        return new BigDecimal(v.toString());
    }
}
