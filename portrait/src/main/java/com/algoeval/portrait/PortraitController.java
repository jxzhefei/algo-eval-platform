package com.algoeval.portrait;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 * 算法画像 API（页面形态对齐产品截图实证 wx_07）：
 * 多日绩效趋势 / 市占率分桶×绩效矩阵（真实桶边界）/ 交易时长分桶 / 最优最差极值 / 自动综述。
 * 离群值分析页签由 ai-insight 的 /outliers 承接。
 */
@RestController
@RequestMapping("/api/portrait")
public class PortraitController {

    private final JdbcTemplate jdbcTemplate;

    public PortraitController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 算法画像：趋势 + 市占率分桶 + 交易时长分桶 + 极值 */
    @GetMapping("/algo/{instance}")
    public Map<String, Object> portrait(@PathVariable String instance,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        LocalDate toDate = parseDate(to, LocalDate.now());
        LocalDate fromDate = parseDate(from, toDate.minusDays(90));

        List<Map<String, Object>> trend = jdbcTemplate.queryForList(
                "SELECT trade_date, order_count, avg_vwap_bps, avg_twap_bps, avg_cancel_ratio, avg_fill_ratio, "
                        + "total_score FROM algo_daily_summary "
                        + "WHERE algo_instance=? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
                instance, Date.valueOf(fromDate), Date.valueOf(toDate));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algoInstance", instance);
        result.put("from", fromDate.toString());
        result.put("to", toDate.toString());
        result.put("trend", trend);
        result.put("participationBuckets", bucketMatrix(instance, fromDate, toDate, true));
        result.put("durationBuckets", bucketMatrix(instance, fromDate, toDate, false));

        Map<String, Object> best = trend.stream()
                .min(Comparator.comparing(PortraitController::absVwap)).orElse(null);
        Map<String, Object> worst = trend.stream()
                .max(Comparator.comparing(PortraitController::absVwap)).orElse(null);
        result.put("bestDay", best);
        result.put("worstDay", worst);
        return result;
    }

    /**
     * 维度分桶 × 绩效矩阵（桶边界为产品实证值）：
     * 市占率（%）：(0,0.1]/(0.1,0.5]/(0.5,1]/(1,5]/(5,10]/(10,100]/(100,∞)
     * 交易时长（分钟）：(0,5]/(5,10]/(10,30]/(30,60]/(60,120]
     */
    private List<Map<String, Object>> bucketMatrix(String instance, LocalDate from, LocalDate to,
                                                   boolean byParticipation) {
        String bucketExpr = byParticipation
                ? "CASE WHEN r.participation*100 <= 0.1 THEN '(0,0.1]' "
                        + "WHEN r.participation*100 <= 0.5 THEN '(0.1,0.5]' "
                        + "WHEN r.participation*100 <= 1 THEN '(0.5,1]' "
                        + "WHEN r.participation*100 <= 5 THEN '(1,5]' "
                        + "WHEN r.participation*100 <= 10 THEN '(5,10]' "
                        + "WHEN r.participation*100 <= 100 THEN '(10,100]' ELSE '(100,∞)' END"
                : "CASE WHEN r.exec_minutes <= 5 THEN '(0,5]' "
                        + "WHEN r.exec_minutes <= 10 THEN '(5,10]' "
                        + "WHEN r.exec_minutes <= 30 THEN '(10,30]' "
                        + "WHEN r.exec_minutes <= 60 THEN '(30,60]' ELSE '(60,120]' END";
        String orderExpr = byParticipation ? "MIN(r.participation)" : "MIN(r.exec_minutes)";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + bucketExpr + " AS bucket, COUNT(*) AS parent_orders, "
                        + "SUM(p.filled_amount)/10000 AS turnover, "
                        + "AVG(r.vwap_perf_bps) AS vwap_perf_bps, AVG(r.vwap_ot_perf_bps) AS vwap_ot_perf_bps, "
                        + "AVG(r.twap_perf_bps) AS twap_perf_bps, AVG(r.twap_ot_perf_bps) AS twap_ot_perf_bps, "
                        + "AVG(r.arrival_perf_bps) AS arrival_perf_bps, AVG(r.open_perf_bps) AS open_perf_bps, "
                        + "AVG(r.close_perf_bps) AS close_perf_bps "
                        + "FROM indicator_result r JOIN parent_order p ON r.order_id=p.order_id "
                        + "AND r.trade_date=p.trade_date "
                        + "WHERE r.algo_instance=? AND r.trade_date BETWEEN ? AND ? AND p.algo_type<>'T0' "
                        + "GROUP BY bucket ORDER BY " + orderExpr,
                instance, Date.valueOf(from), Date.valueOf(to));

        // 成交金额占比（分桶内成交额 / 总成交额）
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            total = total.add(toDecimal(row.get("turnover")));
        }
        for (Map<String, Object> row : rows) {
            BigDecimal t = toDecimal(row.get("turnover"));
            row.put("turnover_share", total.signum() == 0 ? BigDecimal.ZERO
                    : t.divide(total, 4, RoundingMode.HALF_UP));
        }
        return rows;
    }

    /** 自动文字综述（模板结构对齐产品实证：总量概括 + 指标串 + 极值提取 + 分桶建议） */
    @GetMapping("/algo/{instance}/narrative")
    public Map<String, Object> narrative(@PathVariable String instance,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        Map<String, Object> portrait = portrait(instance, from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algoInstance", instance);

        List<Map<String, Object>> trend = (List<Map<String, Object>>) portrait.get("trend");
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) portrait.get("participationBuckets");
        if (trend == null || trend.isEmpty()) {
            result.put("narrative", "区间内暂无评价数据。");
            return result;
        }
        long orderCount = trend.stream().mapToLong(r -> ((Number) r.get("order_count")).longValue()).sum();
        BigDecimal turnover = BigDecimal.ZERO;
        for (Map<String, Object> row : trend) {
            turnover = turnover.add(toDecimal(row.get("avg_fill_ratio")));
        }
        Map<String, Object> best = (Map<String, Object>) portrait.get("bestDay");
        Map<String, Object> worst = (Map<String, Object>) portrait.get("worstDay");

        StringBuilder sb = new StringBuilder();
        sb.append("在 ").append(portrait.get("from")).append(" 至 ").append(portrait.get("to"))
                .append(" 期间，算法 ").append(instance).append(" 累计评价母单 ").append(orderCount)
                .append(" 笔。");
        if (best != null) {
            sb.append("期间表现最优的交易日为 ").append(best.get("trade_date"))
                    .append("（平均 VWAP 绩效 ").append(best.get("avg_vwap_bps")).append(" BPS）");
            if (worst != null) {
                sb.append("；最差为 ").append(worst.get("trade_date")).append("（")
                        .append(worst.get("avg_vwap_bps")).append(" BPS）");
            }
            sb.append("。");
        }
        // 分桶建议：绝对绩效最优的市占率桶
        Map<String, Object> bestBucket = buckets.stream()
                .filter(b -> b.get("vwap_perf_bps") != null)
                .min(Comparator.comparing(b -> toDecimal(b.get("vwap_perf_bps")).abs()))
                .orElse(null);
        if (bestBucket != null) {
            sb.append("市占率 ").append(bestBucket.get("bucket")).append(" 区间绩效最优（平均 VWAP 绩效 ")
                    .append(bestBucket.get("vwap_perf_bps")).append(" BPS），建议在该流动性区间内优先启用本算法实例。");
        }
        result.put("narrative", sb.toString());
        return result;
    }

    /** 算法实例清单（画像页下拉） */
    @GetMapping("/instances")
    public List<String> instances() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT algo_instance FROM algo_daily_summary ORDER BY algo_instance", String.class);
    }

    private static BigDecimal absVwap(Map<String, Object> row) {
        Object v = row.get("avg_vwap_bps");
        return v == null ? BigDecimal.ZERO : toDecimal(v).abs();
    }

    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(((Number) v).doubleValue());
    }

    private static LocalDate parseDate(String s, LocalDate def) {
        return s == null || s.isBlank() ? def : LocalDate.parse(s);
    }
}
