package com.algoeval.offline.indicator;

import com.algoeval.offline.dao.MarketDao;
import com.algoeval.offline.domain.ChildOrderRow;
import com.algoeval.offline.domain.ParentOrderRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 执行质量类指标（6 个）：撤单率/废单率/完成率/市占率/市场冲击/执行时长。
 */
public final class ExecStrategies {

    private ExecStrategies() {
    }

    /** 撤单率 = 撤单笔数 / 总委托笔数 */
    @Component
    public static class CancelRatioStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "cancel_ratio";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            if (children.isEmpty()) {
                return BigDecimal.ZERO;
            }
            long cancelled = children.stream().filter(ChildOrderRow::cancelled).count();
            return BigDecimal.valueOf(cancelled)
                    .divide(BigDecimal.valueOf(children.size()), 4, RoundingMode.HALF_UP);
        }
    }

    /** 废单率 = 废单笔数 / 总委托笔数（流动性不足等被拒绝，与撤单口径独立） */
    @Component
    public static class RejectRatioStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "reject_ratio";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            if (children.isEmpty()) {
                return BigDecimal.ZERO;
            }
            long rejected = children.stream().filter(ChildOrderRow::rejected).count();
            return BigDecimal.valueOf(rejected)
                    .divide(BigDecimal.valueOf(children.size()), 4, RoundingMode.HALF_UP);
        }
    }

    /** 完成率 = 成交金额 / 任务金额 */
    @Component
    public static class FillRatioStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "fill_ratio";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            if (order.taskAmount().signum() == 0) {
                return BigDecimal.ZERO;
            }
            return order.filledAmount().divide(order.taskAmount(), 4, RoundingMode.HALF_UP);
        }
    }

    /** 市占率 = 母单成交量 / 标的当日市场总成交量 */
    @Component
    public static class ParticipationStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "participation";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            MarketDao.SymbolStat stat = market.get(order.symbol());
            if (stat == null || stat.dayVolume() == 0) {
                return BigDecimal.ZERO;
            }
            long filledQty = children.stream().mapToLong(ChildOrderRow::filledQty).sum();
            return BigDecimal.valueOf(filledQty)
                    .divide(BigDecimal.valueOf(stat.dayVolume()), 4, RoundingMode.HALF_UP);
        }
    }

    /** 市场冲击 = 各子单成交价相对当时市价的方向调整偏离均值（bps） */
    @Component
    public static class ImpactBpsStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "impact_bps";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            MarketDao.SymbolStat stat = market.get(order.symbol());
            if (stat == null) {
                return null;
            }
            BigDecimal sum = BigDecimal.ZERO;
            int n = 0;
            for (ChildOrderRow c : children) {
                if (c.filledQty() > 0 && c.avgFillPrice() != null) {
                    BigDecimal tickPrice = stat.priceAt(c.execTime());
                    BigDecimal bps = IndicatorStrategy.sideAdjustedBps(order, c.avgFillPrice(), tickPrice);
                    if (bps != null) {
                        sum = sum.add(bps);
                        n++;
                    }
                }
            }
            return n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        }
    }

    /** 执行时长 = 首笔成交到末笔成交的分钟数 */
    @Component
    public static class ExecMinutesStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "exec_minutes";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            var fills = children.stream().filter(c -> c.filledQty() > 0).toList();
            if (fills.size() < 2) {
                return BigDecimal.ZERO;
            }
            long minutes = Duration.between(fills.get(0).execTime(),
                    fills.get(fills.size() - 1).execTime()).toMinutes();
            return BigDecimal.valueOf(minutes).setScale(2, RoundingMode.HALF_UP);
        }
    }
}
