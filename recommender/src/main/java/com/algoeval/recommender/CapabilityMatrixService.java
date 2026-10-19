package com.algoeval.recommender;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 算法能力画像矩阵（V3 方向二：决策闭环的核心资产）。
 * <p>
 * 统计口径：算法实例 × 标的流动性分桶 × 订单规模（参与率）分桶 × 方向 → 历史绩效分布。
 * 刻意不上深度学习——金融客户要可解释性："为什么推荐 kf_vwap_plus" 必须能用
 * "该类标的近三月 VWAP 绩效均值 X bps，优于第二名的 Y bps"回答（风控红线）。
 * <p>
 * 分桶边界与画像页（portrait）保持一致，绝对阈值在代码中显式声明，可复现可解释。
 */
@Service
public class CapabilityMatrixService {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 流动性分桶阈值：标的日成交额（元） */
    static final BigDecimal LIQ_MID = BigDecimal.valueOf(500_000_000);   // 5 亿
    static final BigDecimal LIQ_HIGH = BigDecimal.valueOf(3_000_000_000L); // 30 亿

    /** 参与率分桶边界（与画像页分桶对齐） */
    static final BigDecimal[] PART_EDGES = {
            new BigDecimal("0.0005"), new BigDecimal("0.001"), new BigDecimal("0.002")
    };
    static final String[] PART_BUCKETS = {"[0,0.05%)", "[0.05,0.1%)", "[0.1,0.2%)", "[0.2,100]"};

    private final JdbcTemplate jdbcTemplate;

    public CapabilityMatrixService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 矩阵单元格统计 */
    public record CellStat(String algoInstance, String liquidityBucket, String scaleBucket, String side,
                           int orderCount, BigDecimal avgVwapBps, BigDecimal avgCancelRatio,
                           BigDecimal avgFillRatio, BigDecimal avgImpactBps, BigDecimal score) {
    }

    /** 单算法在某一匹配口径下的期望绩效（含收缩加权） */
    public record AlgoExpectation(String algoInstance, int sampleCount, BigDecimal expectedVwapBps,
                                  BigDecimal expectedCancelRatio, BigDecimal expectedFillRatio,
                                  BigDecimal expectedScore, boolean exactMatch) {
    }

    /** 流动性分桶：标的日成交额 → 低/中/高 */
    static String liquidityBucket(BigDecimal dayTurnover) {
        if (dayTurnover == null) {
            return "未知";
        }
        if (dayTurnover.compareTo(LIQ_MID) < 0) {
            return "低流动性";
        }
        return dayTurnover.compareTo(LIQ_HIGH) < 0 ? "中流动性" : "高流动性";
    }

    /** 参与率分桶（与画像页边界一致） */
    static String scaleBucket(BigDecimal participation) {
        if (participation == null) {
            return PART_BUCKETS[0];
        }
        for (int i = 0; i < PART_EDGES.length; i++) {
            if (participation.compareTo(PART_EDGES[i]) < 0) {
                return PART_BUCKETS[i];
            }
        }
        return PART_BUCKETS[3];
    }

    /**
     * 构建能力矩阵：一次拉取区间内全部母单级指标，按（算法 × 流动性 × 规模 × 方向）分桶聚合。
     * 流动性来自行情分表的日成交额——分表按日存在，缺失的日期降级为"未知"桶。
     */
    public Map<String, List<CellStat>> buildMatrix(LocalDate from, LocalDate to) {
        // 1. 各交易日各标的的成交额（流动性来源）
        Map<String, BigDecimal> dayTurnoverByDateSymbol = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            final LocalDate day = d;
            String table = "market_tick_" + d.format(SUFFIX);
            try {
                jdbcTemplate.query("SELECT symbol, SUM(turnover) AS tno FROM " + table + " GROUP BY symbol",
                        rs -> {
                            dayTurnoverByDateSymbol.put(day + "|" + rs.getString("symbol"),
                                    rs.getBigDecimal("tno"));
                        });
            } catch (org.springframework.dao.DataAccessException e) {
                // 当日行情分表不存在（非交易日/未采集），跳过——矩阵只统计有数据的日期
            }
        }

        // 2. 母单级指标 + 订单特征
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.algo_instance, r.trade_date, p.symbol, p.side, r.vwap_perf_bps, "
                        + "r.cancel_ratio, r.fill_ratio, r.impact_bps, r.participation "
                        + "FROM indicator_result r JOIN parent_order p "
                        + "ON r.order_id = p.order_id AND r.trade_date = p.trade_date "
                        + "WHERE r.trade_date BETWEEN ? AND ?",
                Date.valueOf(from), Date.valueOf(to));

        // 3. 分桶聚合（内存聚合，demo 规模 3000 行/日；生产口径走 ClickHouse 物化视图预聚合）
        Map<String, Agg> cells = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String algo = (String) row.get("algo_instance");
            String symbol = (String) row.get("symbol");
            String side = (String) row.get("side");
            LocalDate tradeDate = ((java.sql.Date) row.get("trade_date")).toLocalDate();
            String liq = liquidityBucket(dayTurnoverByDateSymbol.get(tradeDate + "|" + symbol));
            String scale = scaleBucket(toBd(row.get("participation")));
            Agg agg = cells.computeIfAbsent(algo + "|" + liq + "|" + scale + "|" + side,
                    k -> new Agg(algo, liq, scale, side));
            agg.add(toBd(row.get("vwap_perf_bps")), toBd(row.get("cancel_ratio")),
                    toBd(row.get("fill_ratio")), toBd(row.get("impact_bps")));
        }

        Map<String, List<CellStat>> matrix = new HashMap<>();
        for (Agg agg : cells.values()) {
            if (agg.count == 0) {
                continue;
            }
            matrix.computeIfAbsent(agg.algo, k -> new ArrayList<>()).add(agg.toStat());
        }
        return matrix;
    }

    /**
     * 期望绩效评估（收缩估计：桶内样本不足时向算法全局均值收缩）。
     * 贝叶斯收缩 w = n/(n+K)：小样本时不敢下重注——推荐系统的谦逊。
     */
    public List<AlgoExpectation> expectations(Map<String, List<CellStat>> matrix,
                                              String liquidityBucket, String scaleBucket, String side) {
        List<AlgoExpectation> result = new ArrayList<>();
        for (Map.Entry<String, List<CellStat>> entry : matrix.entrySet()) {
            String algo = entry.getKey();
            List<CellStat> stats = entry.getValue();
            Agg global = new Agg(algo, "全部", "全部", "全部");
            stats.forEach(s -> global.merge(s));
            if (global.count == 0) {
                continue;
            }
            Agg exact = new Agg(algo, liquidityBucket, scaleBucket, side);
            for (CellStat s : stats) {
                if (s.liquidityBucket().equals(liquidityBucket) && s.scaleBucket().equals(scaleBucket)
                        && s.side().equals(side)) {
                    exact.merge(s);
                }
            }
            boolean exactMatch = exact.count > 0;
            // 收缩：exact 与 global 按 w 加权（无 exact 命中时 w=0）
            final int shrinkageK = 20;
            double w = exact.count / (double) (exact.count + shrinkageK);
            BigDecimal vwap = blend(exact.avgVwap(), global.avgVwap(), w);
            BigDecimal cancel = blend(exact.avgCancel(), global.avgCancel(), w);
            BigDecimal fill = blend(exact.avgFill(), global.avgFill(), w);
            int effectiveN = exact.count + global.count / 4; // 置信样本量的粗略折算
            result.add(new AlgoExpectation(algo, effectiveN,
                    vwap.setScale(4, RoundingMode.HALF_UP), cancel.setScale(4, RoundingMode.HALF_UP),
                    fill.setScale(4, RoundingMode.HALF_UP),
                    score(vwap, cancel, fill, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                    exactMatch));
        }
        return result;
    }

    /** 标的当日成交额（路由请求时实时查行情分表；缺失返回 null → 流动性"未知"桶） */
    public BigDecimal symbolDayTurnover(LocalDate tradeDate, String symbol) {
        String table = "market_tick_" + tradeDate.format(SUFFIX);
        try {
            List<BigDecimal> r = jdbcTemplate.query(
                    "SELECT SUM(turnover) FROM " + table + " WHERE symbol=?",
                    (rs, i) -> rs.getBigDecimal(1), symbol);
            return r.isEmpty() ? null : r.get(0);
        } catch (org.springframework.dao.DataAccessException e) {
            return null;
        }
    }

    /** 已有评价数据的算法实例清单（路由候选池） */
    public List<String> algoInstances() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT algo_instance FROM algo_daily_summary ORDER BY algo_instance", String.class);
    }

    /** 可路由标的清单（当日已有评价数据的标的 + 流动性标签，前端下拉用） */
    public List<Map<String, Object>> symbolsWithTurnover(LocalDate tradeDate) {
        BigDecimal threshold = BigDecimal.valueOf(200_000_000);
        List<Map<String, Object>> symbols = jdbcTemplate.queryForList(
                "SELECT DISTINCT p.symbol FROM parent_order p JOIN indicator_result r "
                        + "ON r.order_id = p.order_id AND r.trade_date = p.trade_date "
                        + "WHERE p.trade_date=? ORDER BY p.symbol",
                Date.valueOf(tradeDate));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : symbols) {
            String symbol = (String) row.get("symbol");
            BigDecimal turnover = symbolDayTurnover(tradeDate, symbol);
            Map<String, Object> item = new HashMap<>();
            item.put("symbol", symbol);
            item.put("dayTurnover", turnover);
            item.put("highTurnover", turnover != null && turnover.compareTo(threshold) >= 0);
            result.add(item);
        }
        return result;
    }

    /**
     * 综合得分（demo 口径，与离线计算 ScoringService 同构——绩效40% 完成度20% 稳定性20% 风险10% 冲击10%；
     * 矩阵单元格维度缺失稳定性样本时以中性值近似）。
     */
    static BigDecimal score(BigDecimal avgVwapBps, BigDecimal avgCancelRatio,
                            BigDecimal avgFillRatio, BigDecimal avgImpactBps) {
        double perf = Math.max(0, 100 - 10 * abs(avgVwapBps));
        double fill = 100 * clamp(avgFillRatio);
        double risk = 100 * (1 - clamp(avgCancelRatio));
        double impact = Math.max(0, 100 - 20 * abs(avgImpactBps));
        double stability = 100 / (1 + 0.1 * abs(avgVwapBps)); // 无逐单样本时以绩效均值近似
        return BigDecimal.valueOf(0.4 * perf + 0.2 * fill + 0.2 * stability + 0.1 * risk + 0.1 * impact)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal blend(BigDecimal exact, BigDecimal global, double w) {
        if (w <= 0) {
            return global;
        }
        if (exact == null) {
            return global;
        }
        return exact.multiply(BigDecimal.valueOf(w)).add(global.multiply(BigDecimal.valueOf(1 - w)));
    }

    private static double abs(BigDecimal v) {
        return v == null ? 0 : v.abs().doubleValue();
    }

    private static double clamp(BigDecimal v) {
        double d = v == null ? 0 : v.doubleValue();
        return Math.max(0, Math.min(1, d));
    }

    static BigDecimal toBd(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }

    /** 分桶聚合器 */
    private static final class Agg {
        final String algo;
        final String liq;
        final String scale;
        final String side;
        int count;
        BigDecimal sumVwap = BigDecimal.ZERO;
        BigDecimal sumCancel = BigDecimal.ZERO;
        BigDecimal sumFill = BigDecimal.ZERO;
        BigDecimal sumImpact = BigDecimal.ZERO;
        int impactN;

        Agg(String algo, String liq, String scale, String side) {
            this.algo = algo;
            this.liq = liq;
            this.scale = scale;
            this.side = side;
        }

        void add(BigDecimal vwap, BigDecimal cancel, BigDecimal fill, BigDecimal impact) {
            count++;
            if (vwap != null) {
                sumVwap = sumVwap.add(vwap);
            }
            if (cancel != null) {
                sumCancel = sumCancel.add(cancel);
            }
            if (fill != null) {
                sumFill = sumFill.add(fill);
            }
            if (impact != null) {
                sumImpact = sumImpact.add(impact);
                impactN++;
            }
        }

        void merge(CellStat s) {
            count += s.orderCount();
            if (s.avgVwapBps() != null) {
                sumVwap = sumVwap.add(s.avgVwapBps().multiply(BigDecimal.valueOf(s.orderCount())));
            }
            if (s.avgCancelRatio() != null) {
                sumCancel = sumCancel.add(s.avgCancelRatio().multiply(BigDecimal.valueOf(s.orderCount())));
            }
            if (s.avgFillRatio() != null) {
                sumFill = sumFill.add(s.avgFillRatio().multiply(BigDecimal.valueOf(s.orderCount())));
            }
            if (s.avgImpactBps() != null) {
                sumImpact = sumImpact.add(s.avgImpactBps().multiply(BigDecimal.valueOf(s.orderCount())));
                impactN += s.orderCount();
            }
        }

        CellStat toStat() {
            BigDecimal n = BigDecimal.valueOf(count);
            return new CellStat(algo, liq, scale, side, count,
                    sumVwap.divide(n, 4, RoundingMode.HALF_UP),
                    sumCancel.divide(n, 4, RoundingMode.HALF_UP),
                    sumFill.divide(n, 4, RoundingMode.HALF_UP),
                    impactN == 0 ? BigDecimal.ZERO : sumImpact.divide(BigDecimal.valueOf(impactN), 4, RoundingMode.HALF_UP),
                    score(avgVwap(), avgCancel(), avgFill(),
                            impactN == 0 ? BigDecimal.ZERO : avgImpact()));
        }

        BigDecimal avgVwap() {
            return count == 0 ? BigDecimal.ZERO : sumVwap.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        }

        BigDecimal avgCancel() {
            return count == 0 ? BigDecimal.ZERO : sumCancel.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        }

        BigDecimal avgFill() {
            return count == 0 ? BigDecimal.ZERO : sumFill.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        }

        BigDecimal avgImpact() {
            return impactN == 0 ? BigDecimal.ZERO : sumImpact.divide(BigDecimal.valueOf(impactN), 4, RoundingMode.HALF_UP);
        }
    }
}
