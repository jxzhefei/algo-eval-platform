package com.algoeval.storer;

import com.algoeval.infra.ck.CkExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL → ClickHouse 日同步与对账（对齐设计文档 §7.3/§7.5）：
 * 日定时读取 MySQL 当日变更数据，jdbc 批量写入 CK（每批 1 万条，实测均衡点）；
 * 每日对账兜底（行数比对），CK 不可达时告警跳过（MySQL 为主源，允许最终一致）。
 * CK 表为 ReplacingMergeTree：重复同步同 key 幂等去重。
 */
@Service
@ConditionalOnProperty(name = "algo.ck.enabled", havingValue = "true")
public class CkSyncService {

    private static final Logger log = LoggerFactory.getLogger(CkSyncService.class);
    private static final int BATCH_SIZE = 10_000;

    private final JdbcTemplate mysql;
    private final CkExecutor ck;

    @Value("${algo.ck.sync.auto:true}")
    private boolean autoSync;

    public CkSyncService(@org.springframework.beans.factory.annotation.Qualifier("jdbcTemplate")
                          JdbcTemplate mysql, CkExecutor ck) {
        this.mysql = mysql;
        this.ck = ck;
    }

    /** 启动即确保 CK 表结构（幂等） */
    @jakarta.annotation.PostConstruct
    public void ensureTables() {
        try {
            ck.executeDdl(List.of(CkExecutor.DDL_MARKET_QUOTE, CkExecutor.DDL_INDICATOR_RESULT));
            log.info("[ck-sync] ClickHouse 表结构就绪（market_quote / indicator_result，ReplacingMergeTree）");
        } catch (Exception e) {
            log.warn("[ck-sync] CK 建表失败（稍后同步时重试）: {}", e.getMessage());
        }
    }

    /** 每交易日 20:30 自动同步当日数据（盘后计算完成后） */
    @Scheduled(cron = "0 30 20 * * MON-FRI")
    public void autoSyncToday() {
        if (autoSync) {
            syncDay(LocalDate.now());
        }
    }

    /** 同步一个交易日的行情与指标结果到 CK */
    public Map<String, Object> syncDay(LocalDate tradeDate) {
        Map<String, Object> report = new LinkedHashMap<>();
        if (!ck.healthy()) {
            log.warn("[ck-sync] ClickHouse 不可达，跳过 {} 同步（MySQL 为主源，允许最终一致）", tradeDate);
            report.put("skipped", "ck-unreachable");
            return report;
        }
        ensureTables();
        Date date = Date.valueOf(tradeDate);

        long marketRows = syncMarketQuotes(tradeDate, date);
        long indicatorRows = syncIndicatorResults(tradeDate, date);
        report.put("marketQuote", marketRows);
        report.put("indicatorResult", indicatorRows);
        log.info("[ck-sync] {} 同步完成：行情 {} 行，指标 {} 行", tradeDate, marketRows, indicatorRows);
        report.putAll(reconcile(tradeDate));
        return report;
    }

    private long syncMarketQuotes(LocalDate tradeDate, Date date) {
        String insert = "INSERT INTO market_quote (trade_date, symbol, tick_time, price, volume, turnover) "
                + "VALUES (?,?,?,?,?,?)";
        List<Object[]> buffer = new ArrayList<>(BATCH_SIZE);
        long[] total = {0};
        mysql.query("SELECT symbol, tick_time, price, volume, turnover FROM market_tick WHERE trade_date=?",
                rs -> {
                    buffer.add(new Object[]{date, rs.getString(1), rs.getTimestamp(2),
                            rs.getBigDecimal(3), rs.getLong(4), rs.getBigDecimal(5)});
                    if (buffer.size() >= BATCH_SIZE) {
                        total[0] += ck.batchInsert(insert, buffer);
                        buffer.clear();
                    }
                }, date);
        if (!buffer.isEmpty()) {
            total[0] += ck.batchInsert(insert, buffer);
        }
        return total[0];
    }

    private long syncIndicatorResults(LocalDate tradeDate, Date date) {
        String insert = "INSERT INTO indicator_result (order_id, trade_date, algo_instance, client_name, "
                + "vwap_perf_bps, vwap_ot_perf_bps, twap_perf_bps, twap_ot_perf_bps, arrival_perf_bps, "
                + "open_perf_bps, close_perf_bps, vwap_perf_pnl, twap_perf_pnl, arrival_perf_pnl, "
                + "open_perf_pnl, close_perf_pnl, cancel_ratio, reject_ratio, fill_ratio, participation, "
                + "impact_bps, exec_minutes, calc_version, update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        List<Object[]> buffer = new ArrayList<>(BATCH_SIZE);
        long[] total = {0};
        mysql.query("SELECT order_id, algo_instance, client_name, vwap_perf_bps, vwap_ot_perf_bps, "
                        + "twap_perf_bps, twap_ot_perf_bps, arrival_perf_bps, open_perf_bps, close_perf_bps, "
                        + "vwap_perf_pnl, twap_perf_pnl, arrival_perf_pnl, open_perf_pnl, close_perf_pnl, "
                        + "cancel_ratio, reject_ratio, fill_ratio, participation, impact_bps, exec_minutes, "
                        + "calc_version, update_time FROM indicator_result WHERE trade_date=?",
                rs -> {
                    buffer.add(new Object[]{rs.getLong(1), date, rs.getString(2), rs.getString(3),
                            rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                            rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
                            rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getBigDecimal(13),
                            rs.getBigDecimal(14), rs.getBigDecimal(15), rs.getBigDecimal(16),
                            rs.getBigDecimal(17), rs.getBigDecimal(18), rs.getBigDecimal(19),
                            rs.getBigDecimal(20), rs.getBigDecimal(21), rs.getLong(22),
                            rs.getTimestamp(23)});
                    if (buffer.size() >= BATCH_SIZE) {
                        total[0] += ck.batchInsert(insert, buffer);
                        buffer.clear();
                    }
                }, date);
        if (!buffer.isEmpty()) {
            total[0] += ck.batchInsert(insert, buffer);
        }
        return total[0];
    }

    /** 每日对账：MySQL 行数 vs CK 行数（ReplacingMergeTree 合并前可能虚高，偏差为提示非错误） */
    public Map<String, Object> reconcile(LocalDate tradeDate) {
        Map<String, Object> report = new LinkedHashMap<>();
        Date date = Date.valueOf(tradeDate);
        Long mysqlMarket = mysql.queryForObject(
                "SELECT COUNT(*) FROM market_tick WHERE trade_date=?", Long.class, date);
        Long mysqlIndicator = mysql.queryForObject(
                "SELECT COUNT(*) FROM indicator_result WHERE trade_date=?", Long.class, date);
        long ckMarket = ck.count("market_quote", "trade_date", date);
        long ckIndicator = ck.count("indicator_result", "trade_date", date);
        report.put("mysqlMarket", mysqlMarket);
        report.put("ckMarket", ckMarket);
        report.put("mysqlIndicator", mysqlIndicator);
        report.put("ckIndicator", ckIndicator);
        if (ckMarket >= 0 && mysqlMarket != null && ckMarket < mysqlMarket) {
            log.warn("[ck-sync] 对账差异：market_quote MySQL={} CK={}（重跑 syncDay 补齐）", mysqlMarket, ckMarket);
        }
        return report;
    }
}
