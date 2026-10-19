package com.algoeval.infra.ck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClickHouse 访问执行器（对齐设计文档 §7）：jdbc 直连、批量写入（~1 万条/批）。
 * algo.ck.enabled=true 时激活；不可达时由调用侧降级 MySQL（对账任务负责发现差异）。
 */
@Component
@ConditionalOnProperty(name = "algo.ck.enabled", havingValue = "true")
public class CkExecutor {

    private static final Logger log = LoggerFactory.getLogger(CkExecutor.class);

    private final String url;
    private final String username;
    private final String password;

    public CkExecutor(@Value("${algo.ck.url:jdbc:clickhouse://localhost:8123/default}") String url,
                      @Value("${algo.ck.username:default}") String username,
                      @Value("${algo.ck.password:}") String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    private Connection open() throws Exception {
        return java.sql.DriverManager.getConnection(url, username, password);
    }

    public boolean healthy() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
            return true;
        } catch (Throwable e) {
            log.warn("[ck] 健康检查失败: {}", e.toString());
            return false;
        }
    }

    public void executeDdl(List<String> statements) {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        } catch (Exception e) {
            throw new IllegalStateException("CK DDL 失败: " + e.getMessage(), e);
        }
    }

    /** 批量写入（jdbc batch，批大小由调用方控制——同步服务按 1 万条/批切分） */
    public int batchInsert(String sql, List<Object[]> rows) {
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    ps.setObject(i + 1, row[i]);
                }
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            int total = 0;
            for (int c : counts) {
                total += Math.max(0, c);
            }
            return total;
        } catch (Exception e) {
            throw new IllegalStateException("CK 批量写入失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                List<Map<String, Object>> result = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    result.add(row);
                }
                return result;
            }
        } catch (Exception e) {
            throw new IllegalStateException("CK 查询失败: " + e.getMessage(), e);
        }
    }

    public long count(String table, String dateColumn, Object tradeDate) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count() FROM " + table + " WHERE " + dateColumn + "=?")) {
            ps.setObject(1, tradeDate);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            log.warn("[ck] 计数失败: {}", e.getMessage());
            return -1;
        }
    }

    /** CK DDL 常量（同步服务建表用）。ReplacingMergeTree：重复同步同 key 幂等去重 */
    public static final String DDL_MARKET_QUOTE = """
            CREATE TABLE IF NOT EXISTS market_quote (
                trade_date Date,
                symbol String,
                tick_time DateTime,
                price Decimal(12,4),
                volume UInt64,
                turnover Decimal(18,2)
            ) ENGINE = ReplacingMergeTree() PARTITION BY trade_date ORDER BY (symbol, tick_time)""";

    public static final String DDL_INDICATOR_RESULT = """
            CREATE TABLE IF NOT EXISTS indicator_result (
                order_id UInt64,
                trade_date Date,
                algo_instance LowCardinality(String),
                client_name String,
                vwap_perf_bps Decimal(12,4),
                vwap_ot_perf_bps Decimal(12,4),
                twap_perf_bps Decimal(12,4),
                twap_ot_perf_bps Decimal(12,4),
                arrival_perf_bps Decimal(12,4),
                open_perf_bps Decimal(12,4),
                close_perf_bps Decimal(12,4),
                vwap_perf_pnl Decimal(18,2),
                twap_perf_pnl Decimal(18,2),
                arrival_perf_pnl Decimal(18,2),
                open_perf_pnl Decimal(18,2),
                close_perf_pnl Decimal(18,2),
                cancel_ratio Decimal(8,4),
                reject_ratio Decimal(8,4),
                fill_ratio Decimal(8,4),
                participation Decimal(8,4),
                impact_bps Decimal(12,4),
                exec_minutes Decimal(8,2),
                calc_version UInt64,
                update_time DateTime
            ) ENGINE = ReplacingMergeTree(calc_version) PARTITION BY trade_date ORDER BY (order_id, trade_date)""";
}
