package com.algoeval.offline.dao;

import com.algoeval.offline.domain.IndicatorResultRow;
import com.algoeval.source.sdk.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标结果 DAO：幂等 upsert 是敢做任务级重试的前提——
 * 节点宕机 → 分片被回收重算 → 只会覆盖同 key 数据，不产生脏数据。
 */
@Repository
public class ResultDao {

    private final JdbcTemplate jdbcTemplate;
    private final SqlDialect dialect;

    public ResultDao(JdbcTemplate jdbcTemplate, SqlDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    /** 批量幂等 upsert（calc_version 递增，便于对账） */
    public void batchUpsert(List<IndicatorResultRow> rows) {
        String sql = dialect.upsert("indicator_result", IndicatorResultRow.COLUMNS, 2);
        jdbcTemplate.batchUpdate(sql, rows, 500, (ps, r) -> {
            ps.setLong(1, r.orderId());
            ps.setDate(2, java.sql.Date.valueOf(r.tradeDate()));
            ps.setString(3, r.algoInstance());
            ps.setString(4, r.clientName());
            ps.setBigDecimal(5, r.vwapPerfBps());
            ps.setBigDecimal(6, r.vwapOtPerfBps());
            ps.setBigDecimal(7, r.twapPerfBps());
            ps.setBigDecimal(8, r.twapOtPerfBps());
            ps.setBigDecimal(9, r.arrivalPerfBps());
            ps.setBigDecimal(10, r.openPerfBps());
            ps.setBigDecimal(11, r.closePerfBps());
            ps.setBigDecimal(12, r.vwapPerfPnl());
            ps.setBigDecimal(13, r.twapPerfPnl());
            ps.setBigDecimal(14, r.arrivalPerfPnl());
            ps.setBigDecimal(15, r.openPerfPnl());
            ps.setBigDecimal(16, r.closePerfPnl());
            ps.setBigDecimal(17, r.cancelRatio());
            ps.setBigDecimal(18, r.rejectRatio());
            ps.setBigDecimal(19, r.fillRatio());
            ps.setBigDecimal(20, r.participation());
            ps.setBigDecimal(21, r.impactBps());
            ps.setBigDecimal(22, r.execMinutes());
            ps.setLong(23, r.calcVersion());
            ps.setTimestamp(24, Timestamp.valueOf(r.updateTime()));
        });
    }

    private static final String SELECT_ALL = "SELECT order_id, trade_date, algo_instance, client_name, "
            + "vwap_perf_bps, vwap_ot_perf_bps, twap_perf_bps, twap_ot_perf_bps, arrival_perf_bps, "
            + "open_perf_bps, close_perf_bps, vwap_perf_pnl, twap_perf_pnl, arrival_perf_pnl, "
            + "open_perf_pnl, close_perf_pnl, cancel_ratio, reject_ratio, fill_ratio, participation, "
            + "impact_bps, exec_minutes, calc_version, update_time FROM indicator_result";

    /** 全量导出（验证对账用，按 order_id 排序保证确定性） */
    public List<IndicatorResultRow> exportAll(LocalDate tradeDate) {
        return jdbcTemplate.query(SELECT_ALL + " WHERE trade_date=? ORDER BY order_id",
                (rs, i) -> map(rs), java.sql.Date.valueOf(tradeDate));
    }

    /** 按算法实例读取当日全部指标行（汇总重算用，固定排序保证 BigDecimal 求和确定性） */
    public List<IndicatorResultRow> findByAlgo(String algoInstance, LocalDate tradeDate) {
        return jdbcTemplate.query(SELECT_ALL + " WHERE algo_instance=? AND trade_date=? ORDER BY order_id",
                (rs, i) -> map(rs), algoInstance, java.sql.Date.valueOf(tradeDate));
    }

    private static IndicatorResultRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IndicatorResultRow(rs.getLong("order_id"),
                rs.getDate("trade_date").toLocalDate(), rs.getString("algo_instance"),
                rs.getString("client_name"),
                rs.getBigDecimal("vwap_perf_bps"), rs.getBigDecimal("vwap_ot_perf_bps"),
                rs.getBigDecimal("twap_perf_bps"), rs.getBigDecimal("twap_ot_perf_bps"),
                rs.getBigDecimal("arrival_perf_bps"), rs.getBigDecimal("open_perf_bps"),
                rs.getBigDecimal("close_perf_bps"),
                rs.getBigDecimal("vwap_perf_pnl"), rs.getBigDecimal("twap_perf_pnl"),
                rs.getBigDecimal("arrival_perf_pnl"), rs.getBigDecimal("open_perf_pnl"),
                rs.getBigDecimal("close_perf_pnl"),
                rs.getBigDecimal("cancel_ratio"), rs.getBigDecimal("reject_ratio"),
                rs.getBigDecimal("fill_ratio"), rs.getBigDecimal("participation"),
                rs.getBigDecimal("impact_bps"), rs.getBigDecimal("exec_minutes"),
                rs.getLong("calc_version"), rs.getTimestamp("update_time").toLocalDateTime());
    }

    public long maxCalcVersion(long orderId, LocalDate tradeDate) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT MAX(calc_version) FROM indicator_result WHERE order_id=? AND trade_date=?",
                Long.class, orderId, java.sql.Date.valueOf(tradeDate));
        return v == null ? 0 : v;
    }

    /** 批量查询计算版本（分片一次查出，避免逐单点查） */
    public Map<Long, Long> loadCalcVersions(LocalDate tradeDate, List<Long> orderIds) {
        Map<Long, Long> versions = new HashMap<>();
        for (int from = 0; from < orderIds.size(); from += 500) {
            List<Long> chunk = orderIds.subList(from, Math.min(orderIds.size(), from + 500));
            String placeholders = chunk.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
            List<Object> args = new ArrayList<>();
            args.add(java.sql.Date.valueOf(tradeDate));
            args.addAll(chunk);
            jdbcTemplate.query(
                    "SELECT order_id, calc_version FROM indicator_result WHERE trade_date=? AND order_id IN ("
                            + placeholders + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler)
                            rs -> versions.put(rs.getLong("order_id"), rs.getLong("calc_version")),
                    args.toArray());
        }
        return versions;
    }

    public int count(LocalDate tradeDate) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM indicator_result WHERE trade_date=?", Integer.class,
                java.sql.Date.valueOf(tradeDate));
        return c == null ? 0 : c;
    }

    public void clear(LocalDate tradeDate) {
        jdbcTemplate.update("DELETE FROM indicator_result WHERE trade_date=?", java.sql.Date.valueOf(tradeDate));
    }
}
