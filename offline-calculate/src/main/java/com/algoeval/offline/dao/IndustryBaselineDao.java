package com.algoeval.offline.dao;

import com.algoeval.source.sdk.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行业均值基线 DAO：全平台算法实例同维度均值——评分"跑赢/跑平/跑输行业"与
 * 雷达图行业均值叠加（算法对比页）的数据源。
 */
@Repository
public class IndustryBaselineDao {

    private static final String[] COLUMNS = {"trade_date", "dimension", "industry_avg", "sample_count",
            "update_time"};

    private final JdbcTemplate jdbcTemplate;
    private final SqlDialect dialect;

    public IndustryBaselineDao(JdbcTemplate jdbcTemplate, SqlDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    /** 维度 → 汇总列名 */
    private static final Map<String, String> DIMENSION_COLUMNS = Map.of(
            "PERF", "perf_score", "FILL", "fill_score", "STABILITY", "stability_score",
            "RISK", "risk_score", "IMPACT", "impact_score", "TOTAL", "total_score",
            "VWAP", "avg_vwap_bps");

    /** 从当日评分汇总计算全平台基线并写入（幂等 upsert） */
    public void refresh(LocalDate tradeDate) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (Map.Entry<String, String> e : DIMENSION_COLUMNS.entrySet()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT " + e.getValue() + " AS v FROM algo_daily_summary WHERE trade_date=? AND "
                            + e.getValue() + " IS NOT NULL",
                    java.sql.Date.valueOf(tradeDate));
            if (rows.isEmpty()) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> row : rows) {
                sum = sum.add((BigDecimal) row.get("v"));
            }
            BigDecimal avg = sum.divide(BigDecimal.valueOf(rows.size()), 4, RoundingMode.HALF_UP);
            jdbcTemplate.update(dialect.upsert("industry_baseline", COLUMNS, 2),
                    java.sql.Date.valueOf(tradeDate), e.getKey(), avg, rows.size(), now);
        }
    }

    /** 当日基线（dimension → industry_avg） */
    public Map<String, BigDecimal> findByDate(LocalDate tradeDate) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT dimension, industry_avg FROM industry_baseline WHERE trade_date=?",
                rs -> {
                    result.put(rs.getString(1), rs.getBigDecimal(2));
                },
                java.sql.Date.valueOf(tradeDate));
        return result;
    }

    /** 基线趋势（行业均值页：各维度多日走势） */
    public List<Map<String, Object>> trend(LocalDate from, LocalDate to) {
        return jdbcTemplate.queryForList(
                "SELECT trade_date, dimension, industry_avg FROM industry_baseline "
                        + "WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date, dimension",
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
    }
}
