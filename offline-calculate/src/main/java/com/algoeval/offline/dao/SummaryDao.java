package com.algoeval.offline.dao;

import com.algoeval.source.sdk.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 算法实例日汇总 DAO——并发写冲突的发生地，分布式锁保护对象。
 */
@Repository
public class SummaryDao {

    private static final String[] COLUMNS = {"algo_instance", "trade_date", "order_count",
            "avg_vwap_bps", "avg_twap_bps", "avg_cancel_ratio", "avg_fill_ratio",
            "perf_score", "fill_score", "stability_score", "risk_score", "impact_score",
            "total_score", "update_time"};

    private final JdbcTemplate jdbcTemplate;
    private final SqlDialect dialect;

    public SummaryDao(JdbcTemplate jdbcTemplate, SqlDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    /** 汇总行 upsert（锁内读改写后的落库） */
    public void upsertSummary(String algoInstance, LocalDate tradeDate, int orderCount,
                              BigDecimal avgVwapBps, BigDecimal avgTwapBps,
                              BigDecimal avgCancelRatio, BigDecimal avgFillRatio) {
        String sql = dialect.upsert("algo_daily_summary", COLUMNS, 2);
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(sql, algoInstance, java.sql.Date.valueOf(tradeDate), orderCount,
                avgVwapBps, avgTwapBps, avgCancelRatio, avgFillRatio,
                null, null, null, null, null, null, now);
    }

    /** 五维评分回填 */
    public void updateScores(String algoInstance, LocalDate tradeDate,
                             BigDecimal perf, BigDecimal fill, BigDecimal stability,
                             BigDecimal risk, BigDecimal impact, BigDecimal total) {
        jdbcTemplate.update(
                "UPDATE algo_daily_summary SET perf_score=?, fill_score=?, stability_score=?, "
                        + "risk_score=?, impact_score=?, total_score=?, update_time=? "
                        + "WHERE algo_instance=? AND trade_date=?",
                perf, fill, stability, risk, impact, total, Timestamp.from(Instant.now()),
                algoInstance, java.sql.Date.valueOf(tradeDate));
    }

    public List<SummaryRow> findByDate(LocalDate tradeDate) {
        return jdbcTemplate.query(
                "SELECT * FROM algo_daily_summary WHERE trade_date=? ORDER BY algo_instance",
                (rs, i) -> new SummaryRow(rs.getString("algo_instance"),
                        rs.getDate("trade_date").toLocalDate(), rs.getInt("order_count"),
                        rs.getBigDecimal("avg_vwap_bps"), rs.getBigDecimal("avg_twap_bps"),
                        rs.getBigDecimal("avg_cancel_ratio"), rs.getBigDecimal("avg_fill_ratio"),
                        rs.getBigDecimal("perf_score"), rs.getBigDecimal("fill_score"),
                        rs.getBigDecimal("stability_score"), rs.getBigDecimal("risk_score"),
                        rs.getBigDecimal("impact_score"), rs.getBigDecimal("total_score")),
                java.sql.Date.valueOf(tradeDate));
    }

    public boolean scoresMissing(LocalDate tradeDate) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM algo_daily_summary WHERE trade_date=? AND total_score IS NULL",
                Integer.class, java.sql.Date.valueOf(tradeDate));
        return c != null && c > 0;
    }

    public void clear(LocalDate tradeDate) {
        jdbcTemplate.update("DELETE FROM algo_daily_summary WHERE trade_date=?", java.sql.Date.valueOf(tradeDate));
    }

    public record SummaryRow(String algoInstance, LocalDate tradeDate, int orderCount,
                             BigDecimal avgVwapBps, BigDecimal avgTwapBps,
                             BigDecimal avgCancelRatio, BigDecimal avgFillRatio,
                             BigDecimal perfScore, BigDecimal fillScore, BigDecimal stabilityScore,
                             BigDecimal riskScore, BigDecimal impactScore, BigDecimal totalScore) {
    }
}
