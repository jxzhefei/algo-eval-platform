package com.algoeval.offline.indicator;

import com.algoeval.offline.dao.MarketDao;
import com.algoeval.offline.domain.ChildOrderRow;
import com.algoeval.offline.domain.ParentOrderRow;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 指标策略：每个指标一个 Strategy，注册进工厂；
 * 新增指标不改主流程（开闭原则）——100+ 指标持续迭代的关键。
 */
public interface IndicatorStrategy {

    /** 指标名（与 indicator_result 列名一致） */
    String name();

    /**
     * @param order    母单
     * @param children 该母单的全部子单（按时间升序）
     * @param market   当日市场基准（symbol → 统计），共享行情数据的本地缓存
     */
    BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                       Map<String, MarketDao.SymbolStat> market);

    /** 买卖方向调整后的偏离 bps：BUY 为正偏离=更贵=差；SELL 取反 */
    static BigDecimal sideAdjustedBps(ParentOrderRow order, BigDecimal avgFill, BigDecimal benchmark) {
        if (avgFill == null || benchmark == null || benchmark.signum() == 0) {
            return null;
        }
        BigDecimal diff = "BUY".equals(order.side()) ? avgFill.subtract(benchmark) : benchmark.subtract(avgFill);
        return diff.multiply(BigDecimal.valueOf(10_000))
                .divide(benchmark, 4, java.math.RoundingMode.HALF_UP);
    }

    /** 成交均价（成交金额/成交数量） */
    static BigDecimal avgFillPrice(List<ChildOrderRow> children) {
        BigDecimal amount = BigDecimal.ZERO;
        long qty = 0;
        for (ChildOrderRow c : children) {
            if (c.filledQty() > 0 && c.avgFillPrice() != null) {
                amount = amount.add(c.avgFillPrice().multiply(BigDecimal.valueOf(c.filledQty())));
                qty += c.filledQty();
            }
        }
        if (qty == 0) {
            return null;
        }
        return amount.divide(BigDecimal.valueOf(qty), 4, java.math.RoundingMode.HALF_UP);
    }

    /** 执行区间（首笔成交 ~ 末笔成交）；不足两笔退化为单笔时点 */
    static LocalDateTime[] execWindow(List<ChildOrderRow> children) {
        LocalDateTime first = null;
        LocalDateTime last = null;
        for (ChildOrderRow c : children) {
            if (c.filledQty() > 0) {
                if (first == null) {
                    first = c.execTime();
                }
                last = c.execTime();
            }
        }
        return new LocalDateTime[]{first, last};
    }

    /** bps → PNL：偏离基点 × 成交金额 / 10000（方向调整后口径） */
    static BigDecimal bpsToPnl(BigDecimal bps, BigDecimal filledAmount) {
        if (bps == null || filledAmount == null) {
            return null;
        }
        return bps.multiply(filledAmount)
                .divide(BigDecimal.valueOf(10_000), 2, java.math.RoundingMode.HALF_UP);
    }
}
