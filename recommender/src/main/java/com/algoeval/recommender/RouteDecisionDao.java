package com.algoeval.recommender;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 路由决策持久化 + 闭环回流。
 * <p>
 * V3 设计："闭环：评价数据反哺路由，路由结果再被评价"——
 * 每笔推荐落决策表；评价结果产出后回流比对（推荐算法 vs 备选算法的实际绩效），
 * 输出命中率与期望误差，持续校准推荐质量。这就是数据飞轮的落点。
 */
@Repository
public class RouteDecisionDao {

    private final JdbcTemplate jdbcTemplate;

    public RouteDecisionDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 保存一笔路由决策，返回决策 ID */
    public long save(LocalDate tradeDate, String symbol, BigDecimal amount, String side,
                     String liquidityBucket, String scaleBucket, String recommended, String runnerUp,
                     BigDecimal expectedBps, BigDecimal expectedScore, BigDecimal confidence, String reason) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO route_decision(trade_date, symbol, amount, side, liquidity_bucket, "
                            + "scale_bucket, recommended, runner_up, expected_bps, expected_score, "
                            + "confidence, reason, create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setDate(1, Date.valueOf(tradeDate));
            ps.setString(2, symbol);
            ps.setBigDecimal(3, amount);
            ps.setString(4, side);
            ps.setString(5, liquidityBucket);
            ps.setString(6, scaleBucket);
            ps.setString(7, recommended);
            ps.setString(8, runnerUp);
            ps.setBigDecimal(9, expectedBps);
            ps.setBigDecimal(10, expectedScore);
            ps.setBigDecimal(11, confidence);
            ps.setString(12, reason);
            ps.setTimestamp(13, Timestamp.valueOf(java.time.LocalDateTime.now()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    /** 查询某日的全部决策 */
    public List<Map<String, Object>> decisionsOf(LocalDate tradeDate) {
        return jdbcTemplate.queryForList(
                "SELECT decision_id, trade_date, symbol, amount, side, liquidity_bucket, scale_bucket, "
                        + "recommended, runner_up, expected_bps, expected_score, confidence, reason, create_time "
                        + "FROM route_decision WHERE trade_date=? ORDER BY decision_id",
                Date.valueOf(tradeDate));
    }

    /**
     * 闭环回流：推荐决策 vs 实际评价结果。
     * <p>
     * 口径对齐：路由推荐预测的是「同口径桶（流动性 × 市占率 × 方向）内算法间的相对绩效」,
     * 回流评价也用同口径桶的实际平均绩效比对（单标的样本噪声大，桶级口径才是推荐真正声称的东西）。
     * 推荐算法实际 bps ≤ 备选算法记为命中；同时统计期望误差。
     */
    public FeedbackReport evaluate(LocalDate tradeDate) {
        List<Map<String, Object>> decisions = decisionsOf(tradeDate);
        if (decisions.isEmpty()) {
            return new FeedbackReport(tradeDate, 0, 0, 0, null, null, null, List.of());
        }
        String tickTable = "market_tick_" + tradeDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        int evaluated = 0;
        int hits = 0;
        BigDecimal sumExpected = BigDecimal.ZERO;
        BigDecimal sumActual = BigDecimal.ZERO;
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> d : decisions) {
            String recommended = (String) d.get("recommended");
            String runnerUp = (String) d.get("runner_up");
            String side = (String) d.get("side");
            String liq = String.valueOf(d.get("liquidity_bucket"));
            String scale = String.valueOf(d.get("scale_bucket"));
            Map<String, Object> row = new java.util.LinkedHashMap<>(d);
            List<Map<String, Object>> actual;
            try {
                actual = jdbcTemplate.queryForList(
                        "SELECT r.algo_instance, AVG(r.vwap_perf_bps) AS actual_bps "
                                + "FROM indicator_result r JOIN parent_order p "
                                + "ON r.order_id = p.order_id AND r.trade_date = p.trade_date "
                                + "LEFT JOIN (SELECT symbol, SUM(turnover) AS tno FROM " + tickTable
                                + " GROUP BY symbol) t ON t.symbol = p.symbol "
                                + "WHERE r.trade_date=? AND p.side=? AND " + liqCondition(liq)
                                + " AND " + scaleCondition(scale) + " GROUP BY r.algo_instance",
                        Date.valueOf(tradeDate), side);
            } catch (org.springframework.dao.DataAccessException e) {
                row.put("feedback_status", "PENDING");
                rows.add(row);
                continue;
            }
            if (actual.isEmpty()) {
                row.put("feedback_status", "PENDING");
                rows.add(row);
                continue;
            }
            Map<String, BigDecimal> actualByAlgo = new java.util.HashMap<>();
            for (Map<String, Object> a : actual) {
                actualByAlgo.put((String) a.get("algo_instance"),
                        a.get("actual_bps") == null ? null : new BigDecimal(a.get("actual_bps").toString()));
            }
            BigDecimal recActual = actualByAlgo.get(recommended);
            BigDecimal altActual = runnerUp == null ? null : actualByAlgo.get(runnerUp);
            if (recActual == null) {
                row.put("feedback_status", "PENDING");
                rows.add(row);
                continue;
            }
            evaluated++;
            sumActual = sumActual.add(recActual);
            BigDecimal expected = CapabilityMatrixService.toBd(d.get("expected_bps"));
            if (expected != null) {
                sumExpected = sumExpected.add(expected);
            }
            boolean hit = altActual == null || recActual.compareTo(altActual) <= 0;
            if (hit) {
                hits++;
            }
            row.put("actual_bps_recommended", recActual.setScale(4, RoundingMode.HALF_UP));
            row.put("actual_bps_alternative", altActual == null ? null : altActual.setScale(4, RoundingMode.HALF_UP));
            row.put("hit", hit);
            row.put("feedback_status", "EVALUATED");
            rows.add(row);
        }
        BigDecimal hitRate = evaluated == 0 ? null
                : BigDecimal.valueOf(hits).divide(BigDecimal.valueOf(evaluated), 4, RoundingMode.HALF_UP);
        BigDecimal avgExpected = evaluated == 0 ? null
                : sumExpected.divide(BigDecimal.valueOf(evaluated), 4, RoundingMode.HALF_UP);
        BigDecimal avgActual = evaluated == 0 ? null
                : sumActual.divide(BigDecimal.valueOf(evaluated), 4, RoundingMode.HALF_UP);
        return new FeedbackReport(tradeDate, decisions.size(), evaluated, hits, hitRate, avgExpected, avgActual, rows);
    }

    /** 流动性桶 → SQL 条件（阈值与 CapabilityMatrixService 一致） */
    private static String liqCondition(String bucket) {
        return switch (bucket) {
            case "低流动性" -> "t.tno < 500000000";
            case "中流动性" -> "t.tno >= 500000000 AND t.tno < 3000000000";
            case "高流动性" -> "t.tno >= 3000000000";
            default -> "t.tno IS NULL"; // 未知桶
        };
    }

    /** 市占率桶 → SQL 条件（边界与画像页/能力矩阵一致） */
    private static String scaleCondition(String bucket) {
        return switch (bucket) {
            case "[0,0.05%)" -> "r.participation < 0.0005";
            case "[0.05,0.1%)" -> "r.participation >= 0.0005 AND r.participation < 0.001";
            case "[0.1,0.2%)" -> "r.participation >= 0.001 AND r.participation < 0.002";
            default -> "r.participation >= 0.002"; // [0.2,100]
        };
    }

    /** 闭环反馈报告 */
    public record FeedbackReport(LocalDate tradeDate, int totalDecisions, int evaluated, int hits,
                                 BigDecimal hitRate, BigDecimal avgExpectedBps, BigDecimal avgActualBps,
                                 List<Map<String, Object>> decisions) {
    }
}
