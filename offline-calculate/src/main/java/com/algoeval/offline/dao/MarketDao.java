package com.algoeval.offline.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 行情数据访问：从当日行情（逻辑表 market_tick，trade_date 路由分表）聚合各标的市场基准。
 * 每分片加载一次全日分钟序列（本地缓存语义），窗口基准（执行区间/委托区间的 VWAP/TWAP）
 * 与开盘/收盘价在内存中按需计算——100+ 指标的公共数据底座。
 */
@Repository
public class MarketDao {

    private final JdbcTemplate jdbcTemplate;

    public MarketDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 单标的分钟行情 */
    public record Bar(BigDecimal price, long volume, BigDecimal turnover) {
    }

    /** 单标的日级统计 + 分钟序列 */
    public record SymbolStat(BigDecimal vwapPrice, BigDecimal timeAvgPrice, long dayVolume,
                             BigDecimal openPrice, BigDecimal closePrice,
                             TreeMap<LocalDateTime, Bar> bars) {

        /** 指定时刻的价格（取不晚于该时刻的最近一分钟） */
        public BigDecimal priceAt(LocalDateTime time) {
            Map.Entry<LocalDateTime, Bar> entry = bars.floorEntry(time);
            if (entry == null) {
                entry = bars.firstEntry();
            }
            return entry == null ? null : entry.getValue().price();
        }

        /** [from, to] 窗口基准：VWAP=Σ成交额/Σ成交量，TWAP=分钟价格均值 */
        public WindowStat windowStat(LocalDateTime from, LocalDateTime to) {
            if (from == null || to == null || bars.isEmpty()) {
                return new WindowStat(vwapPrice, timeAvgPrice);
            }
            NavigableMap<LocalDateTime, Bar> sub = bars.subMap(from, true, to, true);
            if (sub.isEmpty()) {
                return new WindowStat(vwapPrice, timeAvgPrice);
            }
            BigDecimal turnover = BigDecimal.ZERO;
            long volume = 0;
            BigDecimal priceSum = BigDecimal.ZERO;
            for (Bar bar : sub.values()) {
                turnover = turnover.add(bar.turnover());
                volume += bar.volume();
                priceSum = priceSum.add(bar.price());
            }
            BigDecimal vwap = volume == 0 ? null
                    : turnover.divide(BigDecimal.valueOf(volume), 4, RoundingMode.HALF_UP);
            BigDecimal twap = priceSum.divide(BigDecimal.valueOf(sub.size()), 4, RoundingMode.HALF_UP);
            return new WindowStat(vwap, twap);
        }
    }

    /** 窗口基准对 */
    public record WindowStat(BigDecimal vwap, BigDecimal twap) {
    }

    /** 加载当日全标的分钟序列并聚合日级统计 */
    public Map<String, SymbolStat> loadDayStats(LocalDate tradeDate) {
        Map<String, TreeMap<LocalDateTime, Bar>> series = new HashMap<>();
        jdbcTemplate.query(
                "SELECT symbol, tick_time, price, volume, turnover FROM market_tick "
                        + "WHERE trade_date=? ORDER BY symbol, tick_time",
                rs -> {
                    series.computeIfAbsent(rs.getString("symbol"), k -> new TreeMap<>())
                            .put(rs.getTimestamp("tick_time").toLocalDateTime(),
                                    new Bar(rs.getBigDecimal("price"), rs.getLong("volume"),
                                            rs.getBigDecimal("turnover")));
                },
                java.sql.Date.valueOf(tradeDate));

        Map<String, SymbolStat> stats = new HashMap<>();
        for (Map.Entry<String, TreeMap<LocalDateTime, Bar>> e : series.entrySet()) {
            TreeMap<LocalDateTime, Bar> bars = e.getValue();
            long dayVolume = 0;
            BigDecimal turnover = BigDecimal.ZERO;
            BigDecimal priceSum = BigDecimal.ZERO;
            for (Bar bar : bars.values()) {
                dayVolume += bar.volume();
                turnover = turnover.add(bar.turnover());
                priceSum = priceSum.add(bar.price());
            }
            BigDecimal vwap = dayVolume == 0 ? null
                    : turnover.divide(BigDecimal.valueOf(dayVolume), 4, RoundingMode.HALF_UP);
            BigDecimal twap = priceSum.divide(BigDecimal.valueOf(bars.size()), 4, RoundingMode.HALF_UP);
            stats.put(e.getKey(), new SymbolStat(vwap, twap, dayVolume,
                    bars.firstEntry().getValue().price(), bars.lastEntry().getValue().price(), bars));
        }
        return stats;
    }
}
