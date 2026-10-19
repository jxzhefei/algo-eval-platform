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
 * T0 评价 API（T0 双轨，wx_05 实证形态）：
 * T0 概览（交易概览/收益概览/风险收益指标三组指标卡）+ 自动文字综述 + 多日趋势。
 */
@RestController
@RequestMapping("/api/stat/t0")
public class T0Controller {

    private final JdbcTemplate jdbcTemplate;
    private final TwoLevelCache cache;

    public T0Controller(JdbcTemplate jdbcTemplate, TwoLevelCache cache) {
        this.jdbcTemplate = jdbcTemplate;
        this.cache = cache;
    }

    /** T0 概览：三组指标卡 + 综述（数值来自 to_day_detail / t0_order_detail / parent_order） */
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) String date) {
        LocalDate d = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return cache.get("stat:t0overview:" + d, Map.class, () -> buildOverview(d));
    }

    private Map<String, Object> buildOverview(LocalDate d) {
        Date sqlDate = Date.valueOf(d);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", d.toString());

        // 交易概览
        Map<String, Object> trade = new LinkedHashMap<>();
        trade.put("totalTurnover", nvl(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(p.filled_amount),0) FROM parent_order p WHERE p.trade_date=? AND p.algo_type='T0'",
                BigDecimal.class, sqlDate)));
        trade.put("filledQty", nvl(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(p.filled_qty),0) FROM parent_order p WHERE p.trade_date=? AND p.algo_type='T0'",
                Long.class, sqlDate)));
        trade.put("openAmount", nvl(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(d.open_amount),0) FROM to_day_detail d WHERE d.trade_date=?",
                BigDecimal.class, sqlDate)));
        trade.put("childOrders", nvl(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM child_order c JOIN parent_order p ON c.order_id=p.order_id "
                        + "AND c.trade_date=p.trade_date WHERE p.trade_date=? AND p.algo_type='T0'",
                Long.class, sqlDate)));
        trade.put("parentOrders", nvl(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM parent_order WHERE trade_date=? AND algo_type='T0'",
                Long.class, sqlDate)));
        trade.put("taskAmount", nvl(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(task_amount),0) FROM parent_order WHERE trade_date=? AND algo_type='T0'",
                BigDecimal.class, sqlDate)));
        trade.put("symbolCount", nvl(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT symbol) FROM parent_order WHERE trade_date=? AND algo_type='T0'",
                Long.class, sqlDate)));
        trade.put("accountTotal", nvl(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT account_id) FROM parent_order WHERE trade_date=? AND algo_type='T0'",
                Long.class, sqlDate)));
        trade.put("unclosedCount", nvl(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t0_order_detail WHERE trade_date=? AND unclosed_flag=1",
                Long.class, sqlDate)));
        trade.put("cancelRatio", nvl(jdbcTemplate.queryForObject(
                "SELECT AVG(cancel_ratio) FROM to_day_detail WHERE trade_date=?", BigDecimal.class, sqlDate)));
        trade.put("rejectRatio", nvl(jdbcTemplate.queryForObject(
                "SELECT AVG(reject_ratio) FROM to_day_detail WHERE trade_date=?", BigDecimal.class, sqlDate)));
        result.put("tradeOverview", trade);

        // 收益概览 + 风险收益指标（来自 to_day_detail）
        List<Map<String, Object>> dayRows = jdbcTemplate.queryForList(
                "SELECT algo_instance, parent_count, child_count, task_amount, open_amount, net_profit, "
                        + "day_return_bps, win_rate, sharpe, max_drawdown_bps, calmar, open_ratio, unclosed_cnt "
                        + "FROM to_day_detail WHERE trade_date=? ORDER BY algo_instance",
                sqlDate);
        BigDecimal netProfit = BigDecimal.ZERO;
        BigDecimal taskAmount = BigDecimal.ZERO;
        BigDecimal openAmount = BigDecimal.ZERO;
        long parentCount = 0;
        long childCount = 0;
        long unclosed = 0;
        for (Map<String, Object> row : dayRows) {
            netProfit = netProfit.add(toDecimal(row.get("net_profit")));
            taskAmount = taskAmount.add(toDecimal(row.get("task_amount")));
            openAmount = openAmount.add(toDecimal(row.get("open_amount")));
            parentCount += ((Number) row.get("parent_count")).longValue();
            childCount += ((Number) row.get("child_count")).longValue();
            unclosed += ((Number) row.get("unclosed_cnt")).longValue();
        }
        Map<String, Object> profit = new LinkedHashMap<>();
        profit.put("netProfit", netProfit.setScale(2, RoundingMode.HALF_UP));
        profit.put("openAmount", openAmount.setScale(2, RoundingMode.HALF_UP));
        profit.put("accumulatedReturnPct", taskAmount.signum() == 0 ? BigDecimal.ZERO
                : netProfit.multiply(BigDecimal.valueOf(100))
                        .divide(taskAmount, 4, RoundingMode.HALF_UP));
        result.put("profitOverview", profit);

        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("openRatio", taskAmount.signum() == 0 ? BigDecimal.ZERO
                : openAmount.divide(taskAmount, 4, RoundingMode.HALF_UP));
        risk.put("unclosedCount", unclosed);
        if (!dayRows.isEmpty()) {
            Map<String, Object> first = dayRows.get(0);
            risk.put("dayReturnBps", first.get("day_return_bps"));
            risk.put("sharpe", first.get("sharpe"));
            risk.put("maxDrawdownBps", first.get("max_drawdown_bps"));
            risk.put("calmar", first.get("calmar"));
        }
        result.put("riskMetrics", risk);
        result.put("byAlgo", dayRows);
        result.put("narrative", narrative(d, trade, profit, risk));
        return result;
    }

    /** T0 概览自动综述（产品实证模板：总量概括 + 收益指标 + 极值提取） */
    private String narrative(LocalDate d, Map<String, Object> trade, Map<String, Object> profit,
                             Map<String, Object> risk) {
        Map<String, Object> best = jdbcTemplate.queryForList(
                        "SELECT p.symbol AS symbol, SUM(d.net_pnl) AS pnl FROM t0_order_detail d "
                                + "JOIN parent_order p ON d.order_id=p.order_id AND d.trade_date=p.trade_date "
                                + "WHERE d.trade_date=? GROUP BY p.symbol ORDER BY pnl DESC LIMIT 1", Date.valueOf(d))
                .stream().findFirst().orElse(null);
        Map<String, Object> worst = jdbcTemplate.queryForList(
                        "SELECT p.symbol AS symbol, SUM(d.net_pnl) AS pnl FROM t0_order_detail d "
                                + "JOIN parent_order p ON d.order_id=p.order_id AND d.trade_date=p.trade_date "
                                + "WHERE d.trade_date=? GROUP BY p.symbol ORDER BY pnl ASC LIMIT 1", Date.valueOf(d))
                .stream().findFirst().orElse(null);
        StringBuilder sb = new StringBuilder();
        sb.append("在 ").append(d).append(" 交易日，该客户 T0 交易母单 ").append(trade.get("parentOrders"))
                .append(" 笔，任务金额 ").append(trade.get("taskAmount")).append(" 元，总成交金额 ")
                .append(trade.get("totalTurnover")).append(" 元，开仓金额 ").append(profit.get("openAmount"))
                .append(" 元。净收益 ").append(profit.get("netProfit")).append(" 元，累计收益率 ")
                .append(profit.get("accumulatedReturnPct")).append("%。");
        if (best != null) {
            sb.append("期间表现最优的标的为 ").append(best.get("symbol")).append("（净收益 ")
                    .append(best.get("pnl")).append(" 元）");
            if (worst != null) {
                sb.append("，表现最差的标的为 ").append(worst.get("symbol")).append("（净收益 ")
                        .append(worst.get("pnl")).append(" 元）");
            }
            sb.append("。");
        }
        sb.append("未平仓母单 ").append(risk.get("unclosedCount")).append(" 笔。");
        return sb.toString();
    }

    /** T0 多日趋势（每日收益/净收益走势） */
    @GetMapping("/day-detail")
    public List<Map<String, Object>> dayDetail(@RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        LocalDate toDate = to == null ? LocalDate.now() : LocalDate.parse(to);
        LocalDate fromDate = from == null ? toDate.minusDays(90) : LocalDate.parse(from);
        return jdbcTemplate.queryForList(
                "SELECT trade_date, algo_instance, parent_count, child_count, net_profit, day_return_bps, "
                        + "win_rate, sharpe, max_drawdown_bps, open_ratio, unclosed_cnt "
                        + "FROM to_day_detail WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date, algo_instance",
                Date.valueOf(fromDate), Date.valueOf(toDate));
    }

    private static BigDecimal nvl(Object v) {
        return v == null ? BigDecimal.ZERO : toDecimal(v);
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
}
