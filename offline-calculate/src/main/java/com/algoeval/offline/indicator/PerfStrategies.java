package com.algoeval.offline.indicator;

import com.algoeval.offline.dao.MarketDao;
import com.algoeval.offline.domain.ChildOrderRow;
import com.algoeval.offline.domain.ParentOrderRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 绩效类指标（产品实证的 7 种基准，BPS 与 PNL 双口径）：
 * <ul>
 *   <li>VWAP / VWAP(委托时间)：执行区间 / 委托区间 的市场量加权均价基准</li>
 *   <li>TWAP / TWAP(委托时间)：执行区间 / 委托区间 的时间加权均价基准</li>
 *   <li>Arrival：母单到达时刻市场价基准</li>
 *   <li>开盘 / 收盘：当日开盘价 / 收盘价基准</li>
 * </ul>
 * bps 为正 = 跑输基准（BUY 更贵/SELL 更便宜）；PNL = bps × 成交金额 / 10000。
 */
public final class PerfStrategies {

    private PerfStrategies() {
    }

    /** 成交均价（BPS 与 PNL 族共用） */
    private static BigDecimal fill(ParentOrderRow order, List<ChildOrderRow> children) {
        return IndicatorStrategy.avgFillPrice(children);
    }

    // ═══ BPS 口径（7 种基准）═══

    @Component
    public static class VwapPerfBpsStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "vwap_perf_bps";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            MarketDao.SymbolStat stat = market.get(order.symbol());
            if (stat == null) {
                return null;
            }
            LocalDateTime[] win = IndicatorStrategy.execWindow(children);
            return IndicatorStrategy.sideAdjustedBps(order, fill(order, children),
                    stat.windowStat(win[0], win[1]).vwap());
        }
    }

    @Component
    public static class VwapOtPerfBpsStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "vwap_ot_perf_bps";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            MarketDao.SymbolStat stat = market.get(order.symbol());
            if (stat == null) {
                return null;
            }
            return IndicatorStrategy.sideAdjustedBps(order, fill(order, children),
                    stat.windowStat(order.startTime(), order.endTime()).vwap());
        }
    }

    @Component
    public static class TwapPerfBpsStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "twap_perf_bps";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            MarketDao.SymbolStat stat = market.get(order.symbol());
            if (stat == null) {
                return null;
            }
            LocalDateTime[] win = IndicatorStrategy.execWindow(children);
            return IndicatorStrategy.sideAdjustedBps(order, fill(order, children),
                    stat.windowStat(win[0], win[1]).twap());
        }
    }

    @Component
    public static class TwapOtPerfBpsStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "twap_ot_perf_bps";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            MarketDao.SymbolStat stat = market.get(order.symbol());
            if (stat == null) {
                return null;
            }
            return IndicatorStrategy.sideAdjustedBps(order, fill(order, children),
                    stat.windowStat(order.startTime(), order.endTime()).twap());
        }
    }

    @Component
    public static class ArrivalPerfBpsStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "arrival_perf_bps";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            return IndicatorStrategy.sideAdjustedBps(order, fill(order, children), order.arrivalPrice());
        }
    }

    @Component
    public static class OpenPerfBpsStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "open_perf_bps";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            MarketDao.SymbolStat stat = market.get(order.symbol());
            return stat == null ? null
                    : IndicatorStrategy.sideAdjustedBps(order, fill(order, children), stat.openPrice());
        }
    }

    @Component
    public static class ClosePerfBpsStrategy implements IndicatorStrategy {
        @Override
        public String name() {
            return "close_perf_bps";
        }

        @Override
        public BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                  Map<String, MarketDao.SymbolStat> market) {
            MarketDao.SymbolStat stat = market.get(order.symbol());
            return stat == null ? null
                    : IndicatorStrategy.sideAdjustedBps(order, fill(order, children), stat.closePrice());
        }
    }

    // ═══ PNL 口径（偏离基点折算的绝对盈亏金额）═══

    private abstract static class PnlStrategy implements IndicatorStrategy {
        private final String name;
        private final IndicatorStrategy bpsSource;

        protected PnlStrategy(String name, IndicatorStrategy bpsSource) {
            this.name = name;
            this.bpsSource = bpsSource;
        }

        @Override
        public final String name() {
            return name;
        }

        @Override
        public final BigDecimal compute(ParentOrderRow order, List<ChildOrderRow> children,
                                        Map<String, MarketDao.SymbolStat> market) {
            BigDecimal bps = bpsSource.compute(order, children, market);
            return IndicatorStrategy.bpsToPnl(bps, order.filledAmount());
        }
    }

    @Component
    public static class VwapPerfPnlStrategy extends PnlStrategy {
        public VwapPerfPnlStrategy() {
            super("vwap_perf_pnl", new VwapPerfBpsStrategy());
        }
    }

    @Component
    public static class TwapPerfPnlStrategy extends PnlStrategy {
        public TwapPerfPnlStrategy() {
            super("twap_perf_pnl", new TwapPerfBpsStrategy());
        }
    }

    @Component
    public static class ArrivalPerfPnlStrategy extends PnlStrategy {
        public ArrivalPerfPnlStrategy() {
            super("arrival_perf_pnl", new ArrivalPerfBpsStrategy());
        }
    }

    @Component
    public static class OpenPerfPnlStrategy extends PnlStrategy {
        public OpenPerfPnlStrategy() {
            super("open_perf_pnl", new OpenPerfBpsStrategy());
        }
    }

    @Component
    public static class ClosePerfPnlStrategy extends PnlStrategy {
        public ClosePerfPnlStrategy() {
            super("close_perf_pnl", new ClosePerfBpsStrategy());
        }
    }
}
