package com.algoeval.infra.sharding;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按交易日分表的表注册中心：DDL 唯一来源（对齐设计文档 §6.5 数据模型）。
 * 分表（交易明细/行情/指标结果，随交易日增长）与单表（调度/汇总/画像等）在此统一声明。
 */
public final class ShardedTables {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 按交易日分表的逻辑表（路由键 trade_date） */
    public static final List<String> SHARDED = List.of(
            "market_tick", "parent_order", "child_order",
            "t0_order_detail", "to_broker_order_detail", "indicator_result");

    /** 单表（调度/汇总/画像/实时指标——体量可控，不分表） */
    public static final List<String> SINGLE = List.of(
            "compute_task", "algo_daily_summary", "realtime_metric", "route_decision",
            "secu_symbol", "to_day_detail", "industry_baseline");

    /** 表名 → 建表 DDL（%s 为表名占位） */
    private static final Map<String, String> DDL = buildDdl();

    private static Map<String, String> buildDdl() {
        Map<String, String> m = new LinkedHashMap<>();

        m.put("market_tick",
                "CREATE TABLE IF NOT EXISTS %s ("
                        + "symbol VARCHAR(16) NOT NULL, "
                        + "trade_date DATE NOT NULL, "
                        + "tick_time TIMESTAMP NOT NULL, "
                        + "price DECIMAL(12,4) NOT NULL, "
                        + "volume BIGINT NOT NULL, "
                        + "turnover DECIMAL(18,2) NOT NULL, "
                        + "PRIMARY KEY (symbol, tick_time))");

        m.put("parent_order",
                "CREATE TABLE IF NOT EXISTS %s ("
                        + "order_id BIGINT NOT NULL, "
                        + "trade_date DATE NOT NULL, "
                        + "algo_instance VARCHAR(64) NOT NULL, "
                        + "algo_type VARCHAR(16) NOT NULL, "
                        + "algo_vendor VARCHAR(32) NOT NULL, "
                        + "client_name VARCHAR(64) NOT NULL, "
                        + "account_id VARCHAR(32) NOT NULL, "
                        + "symbol VARCHAR(16) NOT NULL, "
                        + "side VARCHAR(4) NOT NULL, "
                        + "task_qty BIGINT NOT NULL, "
                        + "task_amount DECIMAL(18,2) NOT NULL, "
                        + "filled_qty BIGINT NOT NULL, "
                        + "filled_amount DECIMAL(18,2) NOT NULL, "
                        + "avg_fill_price DECIMAL(12,4), "
                        + "arrival_price DECIMAL(12,4) NOT NULL, "
                        + "start_time TIMESTAMP NULL, "
                        + "end_time TIMESTAMP NULL, "
                        + "status VARCHAR(16) NOT NULL, "
                        + "update_time TIMESTAMP NOT NULL, "
                        + "PRIMARY KEY (order_id, trade_date))");

        m.put("child_order",
                "CREATE TABLE IF NOT EXISTS %s ("
                        + "child_id BIGINT NOT NULL, "
                        + "order_id BIGINT NOT NULL, "
                        + "trade_date DATE NOT NULL, "
                        + "side VARCHAR(4) NOT NULL, "
                        + "exec_time TIMESTAMP NOT NULL, "
                        + "qty BIGINT NOT NULL, "
                        + "price DECIMAL(12,4) NOT NULL, "
                        + "filled_qty BIGINT NOT NULL, "
                        + "avg_fill_price DECIMAL(12,4), "
                        + "is_cancelled TINYINT NOT NULL, "
                        + "is_rejected TINYINT NOT NULL, "
                        + "PRIMARY KEY (child_id, trade_date))");

        m.put("t0_order_detail",
                "CREATE TABLE IF NOT EXISTS %s ("
                        + "order_id BIGINT NOT NULL, "
                        + "trade_date DATE NOT NULL, "
                        + "algo_instance VARCHAR(64) NOT NULL, "
                        + "client_name VARCHAR(64) NOT NULL, "
                        + "symbol VARCHAR(16) NOT NULL, "
                        + "side VARCHAR(4) NOT NULL, "
                        + "buy_amount DECIMAL(18,2) NOT NULL, "
                        + "sell_amount DECIMAL(18,2) NOT NULL, "
                        + "buy_qty BIGINT NOT NULL, "
                        + "sell_qty BIGINT NOT NULL, "
                        + "round_trip_cnt INT NOT NULL, "
                        + "net_pnl DECIMAL(18,2) NOT NULL, "
                        + "return_bps DECIMAL(12,4) NOT NULL, "
                        + "unclosed_flag TINYINT NOT NULL, "
                        + "calc_version BIGINT NOT NULL, "
                        + "update_time TIMESTAMP NOT NULL, "
                        + "PRIMARY KEY (order_id, trade_date))");

        m.put("to_broker_order_detail",
                "CREATE TABLE IF NOT EXISTS %s ("
                        + "child_id BIGINT NOT NULL, "
                        + "order_id BIGINT NOT NULL, "
                        + "trade_date DATE NOT NULL, "
                        + "side VARCHAR(4) NOT NULL, "
                        + "exec_time TIMESTAMP NOT NULL, "
                        + "qty BIGINT NOT NULL, "
                        + "price DECIMAL(12,4) NOT NULL, "
                        + "filled_qty BIGINT NOT NULL, "
                        + "avg_fill_price DECIMAL(12,4), "
                        + "is_cancelled TINYINT NOT NULL, "
                        + "is_rejected TINYINT NOT NULL, "
                        + "report_time TIMESTAMP NOT NULL, "
                        + "PRIMARY KEY (child_id, trade_date))");

        m.put("indicator_result",
                "CREATE TABLE IF NOT EXISTS %s ("
                        + "order_id BIGINT NOT NULL, "
                        + "trade_date DATE NOT NULL, "
                        + "algo_instance VARCHAR(64) NOT NULL, "
                        + "client_name VARCHAR(64) NOT NULL, "
                        + "vwap_perf_bps DECIMAL(12,4), "
                        + "vwap_ot_perf_bps DECIMAL(12,4), "
                        + "twap_perf_bps DECIMAL(12,4), "
                        + "twap_ot_perf_bps DECIMAL(12,4), "
                        + "arrival_perf_bps DECIMAL(12,4), "
                        + "open_perf_bps DECIMAL(12,4), "
                        + "close_perf_bps DECIMAL(12,4), "
                        + "vwap_perf_pnl DECIMAL(18,2), "
                        + "twap_perf_pnl DECIMAL(18,2), "
                        + "arrival_perf_pnl DECIMAL(18,2), "
                        + "open_perf_pnl DECIMAL(18,2), "
                        + "close_perf_pnl DECIMAL(18,2), "
                        + "cancel_ratio DECIMAL(8,4), "
                        + "reject_ratio DECIMAL(8,4), "
                        + "fill_ratio DECIMAL(8,4), "
                        + "participation DECIMAL(8,4), "
                        + "impact_bps DECIMAL(12,4), "
                        + "exec_minutes DECIMAL(8,2), "
                        + "calc_version BIGINT NOT NULL, "
                        + "update_time TIMESTAMP NOT NULL, "
                        + "PRIMARY KEY (order_id, trade_date))");

        // ═══ 单表 ═══
        m.put("compute_task",
                "CREATE TABLE IF NOT EXISTS compute_task ("
                        + "trade_date DATE NOT NULL, "
                        + "shard_no INT NOT NULL, "
                        + "status VARCHAR(16) NOT NULL, "
                        + "owner VARCHAR(64), "
                        + "version BIGINT NOT NULL DEFAULT 0, "
                        + "heartbeat_time TIMESTAMP NULL, "
                        + "retry_count INT NOT NULL DEFAULT 0, "
                        + "update_time TIMESTAMP NOT NULL, "
                        + "PRIMARY KEY (trade_date, shard_no))");

        m.put("algo_daily_summary",
                "CREATE TABLE IF NOT EXISTS algo_daily_summary ("
                        + "algo_instance VARCHAR(64) NOT NULL, "
                        + "trade_date DATE NOT NULL, "
                        + "order_count INT NOT NULL, "
                        + "avg_vwap_bps DECIMAL(12,4), "
                        + "avg_twap_bps DECIMAL(12,4), "
                        + "avg_cancel_ratio DECIMAL(8,4), "
                        + "avg_fill_ratio DECIMAL(8,4), "
                        + "perf_score DECIMAL(6,2), "
                        + "fill_score DECIMAL(6,2), "
                        + "stability_score DECIMAL(6,2), "
                        + "risk_score DECIMAL(6,2), "
                        + "impact_score DECIMAL(6,2), "
                        + "total_score DECIMAL(6,2), "
                        + "update_time TIMESTAMP NOT NULL, "
                        + "PRIMARY KEY (algo_instance, trade_date))");

        m.put("realtime_metric",
                "CREATE TABLE IF NOT EXISTS realtime_metric ("
                        + "algo_instance VARCHAR(64) NOT NULL, "
                        + "window_time TIMESTAMP NOT NULL, "
                        + "running_vwap_bps DECIMAL(12,4), "
                        + "cancel_ratio DECIMAL(8,4), "
                        + "active_orders INT NOT NULL, "
                        + "alert_flag TINYINT NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (algo_instance, window_time))");

        m.put("route_decision",
                "CREATE TABLE IF NOT EXISTS route_decision ("
                        + "decision_id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                        + "trade_date DATE NOT NULL, "
                        + "symbol VARCHAR(16) NOT NULL, "
                        + "amount DECIMAL(18,2) NOT NULL, "
                        + "side VARCHAR(4) NOT NULL, "
                        + "liquidity_bucket VARCHAR(16) NOT NULL, "
                        + "scale_bucket VARCHAR(16) NOT NULL, "
                        + "recommended VARCHAR(64) NOT NULL, "
                        + "runner_up VARCHAR(64), "
                        + "expected_bps DECIMAL(12,4), "
                        + "expected_score DECIMAL(6,2), "
                        + "confidence DECIMAL(5,2), "
                        + "reason VARCHAR(1024), "
                        + "create_time TIMESTAMP NOT NULL)");

        m.put("secu_symbol",
                "CREATE TABLE IF NOT EXISTS secu_symbol ("
                        + "symbol VARCHAR(16) NOT NULL, "
                        + "symbol_name VARCHAR(64), "
                        + "exchange VARCHAR(8), "
                        + "list_date DATE, "
                        + "float_shares BIGINT, "
                        + "is_active TINYINT NOT NULL DEFAULT 1, "
                        + "update_time TIMESTAMP NOT NULL, "
                        + "PRIMARY KEY (symbol))");

        m.put("to_day_detail",
                "CREATE TABLE IF NOT EXISTS to_day_detail ("
                        + "trade_date DATE NOT NULL, "
                        + "algo_instance VARCHAR(64) NOT NULL, "
                        + "parent_count INT NOT NULL, "
                        + "child_count INT NOT NULL, "
                        + "task_amount DECIMAL(18,2) NOT NULL, "
                        + "open_amount DECIMAL(18,2) NOT NULL, "
                        + "net_profit DECIMAL(18,2) NOT NULL, "
                        + "day_return_bps DECIMAL(12,4) NOT NULL, "
                        + "win_rate DECIMAL(8,4), "
                        + "sharpe DECIMAL(10,4), "
                        + "max_drawdown_bps DECIMAL(12,4), "
                        + "calmar DECIMAL(10,4), "
                        + "open_ratio DECIMAL(8,4), "
                        + "unclosed_cnt INT NOT NULL, "
                        + "cancel_ratio DECIMAL(8,4), "
                        + "reject_ratio DECIMAL(8,4), "
                        + "update_time TIMESTAMP NOT NULL, "
                        + "PRIMARY KEY (algo_instance, trade_date))");

        m.put("industry_baseline",
                "CREATE TABLE IF NOT EXISTS industry_baseline ("
                        + "trade_date DATE NOT NULL, "
                        + "dimension VARCHAR(16) NOT NULL, "
                        + "industry_avg DECIMAL(10,4) NOT NULL, "
                        + "sample_count INT NOT NULL, "
                        + "update_time TIMESTAMP NOT NULL, "
                        + "PRIMARY KEY (trade_date, dimension))");

        return Collections.unmodifiableMap(m);
    }

    private ShardedTables() {
    }

    public static String physicalName(String logical, LocalDate date) {
        return logical + "_" + date.format(SUFFIX);
    }

    public static String ddl(String tableName) {
        String d = DDL.get(tableName);
        if (d == null) {
            throw new IllegalArgumentException("未注册的表: " + tableName);
        }
        return d;
    }
}
