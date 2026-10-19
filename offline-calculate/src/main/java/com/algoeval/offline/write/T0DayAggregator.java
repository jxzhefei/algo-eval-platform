package com.algoeval.offline.write;

import com.algoeval.source.sdk.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T0 日级汇总（真实表 to_day_detail）：按算法实例聚合当日回转交易——
 * 交易概览/收益概览/风险收益指标三组指标（T0 客户概览页的数据底座）。
 * 单日演示场景下夏普/卡玛以母单级收益分布近似（生产为多日收益序列口径）。
 */
@Component
public class T0DayAggregator {

    private static final Logger log = LoggerFactory.getLogger(T0DayAggregator.class);

    private static final String[] COLUMNS = {"trade_date", "algo_instance", "parent_count", "child_count",
            "task_amount", "open_amount", "net_profit", "day_return_bps", "win_rate", "sharpe",
            "max_drawdown_bps", "calmar", "open_ratio", "unclosed_cnt", "cancel_ratio", "reject_ratio",
            "update_time"};

    private final JdbcTemplate jdbcTemplate;
    private final SqlDialect dialect;

    public T0DayAggregator(JdbcTemplate jdbcTemplate, SqlDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    private record OrderDetail(String algoInstance, BigDecimal taskAmount, BigDecimal buyAmount,
                               BigDecimal sellAmount, BigDecimal netPnl, BigDecimal returnBps, int unclosed) {
    }

    /** 聚合当日全部 T0 算法实例的日级汇总（评分完成后触发，整体幂等覆盖） */
    public void aggregate(LocalDate tradeDate) {
        List<OrderDetail> details = jdbcTemplate.query(
                "SELECT d.algo_instance, p.task_amount, d.buy_amount, d.sell_amount, d.net_pnl, "
                        + "d.return_bps, d.unclosed_flag "
                        + "FROM t0_order_detail d JOIN parent_order p "
                        + "ON d.order_id = p.order_id AND d.trade_date = p.trade_date "
                        + "WHERE d.trade_date=? ORDER BY d.order_id",
                (rs, i) -> new OrderDetail(rs.getString("algo_instance"), rs.getBigDecimal("task_amount"),
                        rs.getBigDecimal("buy_amount"), rs.getBigDecimal("sell_amount"),
                        rs.getBigDecimal("net_pnl"), rs.getBigDecimal("return_bps"),
                        rs.getInt("unclosed_flag")),
                java.sql.Date.valueOf(tradeDate));
        if (details.isEmpty()) {
            return;
        }

        Map<String, List<OrderDetail>> byAlgo = new LinkedHashMap<>();
        for (OrderDetail d : details) {
            byAlgo.computeIfAbsent(d.algoInstance(), k -> new ArrayList<>()).add(d);
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (Map.Entry<String, List<OrderDetail>> e : byAlgo.entrySet()) {
            String algo = e.getKey();
            List<OrderDetail> orders = e.getValue();

            BigDecimal taskAmount = BigDecimal.ZERO;
            BigDecimal openAmount = BigDecimal.ZERO;
            BigDecimal netProfit = BigDecimal.ZERO;
            int wins = 0;
            int unclosed = 0;
            BigDecimal worstBps = BigDecimal.ZERO;
            for (OrderDetail o : orders) {
                taskAmount = taskAmount.add(nvl(o.taskAmount()));
                openAmount = openAmount.add(nvl(o.buyAmount()));
                netProfit = netProfit.add(nvl(o.netPnl()));
                if (nvl(o.netPnl()).signum() > 0) {
                    wins++;
                }
                if (o.unclosed() == 1) {
                    unclosed++;
                }
                if (o.returnBps() != null && o.returnBps().compareTo(worstBps) < 0) {
                    worstBps = o.returnBps();
                }
            }
            BigDecimal dayReturnBps = taskAmount.signum() == 0 ? BigDecimal.ZERO
                    : netProfit.multiply(BigDecimal.valueOf(10_000))
                            .divide(taskAmount, 4, RoundingMode.HALF_UP);
            BigDecimal winRate = BigDecimal.valueOf(wins)
                    .divide(BigDecimal.valueOf(orders.size()), 4, RoundingMode.HALF_UP);
            BigDecimal sharpe = sharpe(orders);
            BigDecimal maxDrawdown = worstBps.abs();
            BigDecimal annualizedBps = dayReturnBps.multiply(BigDecimal.valueOf(252));
            BigDecimal calmar = maxDrawdown.signum() == 0 ? null
                    : annualizedBps.divide(maxDrawdown, 4, RoundingMode.HALF_UP);
            BigDecimal openRatio = taskAmount.signum() == 0 ? null
                    : openAmount.divide(taskAmount, 4, RoundingMode.HALF_UP);

            Integer childCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM child_order c JOIN parent_order p ON c.order_id = p.order_id "
                            + "WHERE p.trade_date = ? AND p.algo_type = 'T0'",
                    Integer.class, java.sql.Date.valueOf(tradeDate));
            Map<String, BigDecimal> ratios = ratios(tradeDate);
            jdbcTemplate.update(dialect.upsert("to_day_detail", COLUMNS, 2),
                    java.sql.Date.valueOf(tradeDate), algo, orders.size(),
                    childCount == null ? 0 : childCount,
                    taskAmount.setScale(2, RoundingMode.HALF_UP), openAmount.setScale(2, RoundingMode.HALF_UP),
                    netProfit.setScale(2, RoundingMode.HALF_UP), dayReturnBps, winRate, sharpe,
                    maxDrawdown, calmar, openRatio, unclosed,
                    ratios.get("cancel"), ratios.get("reject"), now);
            log.info("[offline-calculate] T0 日汇总完成 {}: 母单{} 净收益{} 收益率{}bps 胜率{}%",
                    algo, orders.size(), netProfit.setScale(2, RoundingMode.HALF_UP),
                    dayReturnBps, winRate.multiply(BigDecimal.valueOf(100)));
        }
    }

    private Map<String, BigDecimal> ratios(LocalDate tradeDate) {
        Map<String, BigDecimal> r = new LinkedHashMap<>();
        r.put("cancel", avg("cancel_ratio", tradeDate));
        r.put("reject", avg("reject_ratio", tradeDate));
        return r;
    }

    private BigDecimal avg(String column, LocalDate tradeDate) {
        BigDecimal v = jdbcTemplate.queryForObject(
                "SELECT AVG(" + column + ") FROM indicator_result r JOIN parent_order p "
                        + "ON r.order_id = p.order_id AND r.trade_date = p.trade_date "
                        + "WHERE r.trade_date=? AND p.algo_type='T0'",
                BigDecimal.class, java.sql.Date.valueOf(tradeDate));
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 母单级收益分布近似夏普（单日演示口径；生产为多日收益序列） */
    private static BigDecimal sharpe(List<OrderDetail> orders) {
        List<BigDecimal> returns = orders.stream().map(o -> nvl(o.returnBps())).toList();
        if (returns.size() < 2) {
            return null;
        }
        BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 6, RoundingMode.HALF_UP);
        BigDecimal variance = returns.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size() - 1), 6, RoundingMode.HALF_UP);
        if (variance.signum() == 0) {
            return null;
        }
        double std = Math.sqrt(variance.doubleValue());
        return BigDecimal.valueOf(mean.doubleValue() / std * Math.sqrt(252))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
