-- 离线计算验证三件套的 H2 测试库（逻辑表直建——与 ShardedTables DDL 同构）
CREATE TABLE IF NOT EXISTS parent_order (
    order_id        BIGINT NOT NULL,
    trade_date      DATE NOT NULL,
    algo_instance   VARCHAR(64) NOT NULL,
    algo_type       VARCHAR(16) NOT NULL,
    algo_vendor     VARCHAR(32) NOT NULL,
    client_name     VARCHAR(64) NOT NULL,
    account_id      VARCHAR(32) NOT NULL,
    symbol          VARCHAR(16) NOT NULL,
    side            VARCHAR(4) NOT NULL,
    task_qty        BIGINT NOT NULL,
    task_amount     DECIMAL(18,2) NOT NULL,
    filled_qty      BIGINT NOT NULL,
    filled_amount   DECIMAL(18,2) NOT NULL,
    avg_fill_price  DECIMAL(12,4),
    arrival_price   DECIMAL(12,4) NOT NULL,
    start_time      TIMESTAMP NULL,
    end_time        TIMESTAMP NULL,
    status          VARCHAR(16) NOT NULL,
    update_time     TIMESTAMP NOT NULL,
    PRIMARY KEY (order_id, trade_date)
);

CREATE TABLE IF NOT EXISTS child_order (
    child_id        BIGINT NOT NULL,
    order_id        BIGINT NOT NULL,
    trade_date      DATE NOT NULL,
    side            VARCHAR(4) NOT NULL,
    exec_time       TIMESTAMP NOT NULL,
    qty             BIGINT NOT NULL,
    price           DECIMAL(12,4) NOT NULL,
    filled_qty      BIGINT NOT NULL,
    avg_fill_price  DECIMAL(12,4),
    is_cancelled    TINYINT NOT NULL,
    is_rejected     TINYINT NOT NULL,
    PRIMARY KEY (child_id, trade_date)
);

CREATE TABLE IF NOT EXISTS market_tick (
    symbol          VARCHAR(16) NOT NULL,
    trade_date      DATE NOT NULL,
    tick_time       TIMESTAMP NOT NULL,
    price           DECIMAL(12,4) NOT NULL,
    volume          BIGINT NOT NULL,
    turnover        DECIMAL(18,2) NOT NULL,
    PRIMARY KEY (symbol, tick_time)
);

CREATE TABLE IF NOT EXISTS t0_order_detail (
    order_id        BIGINT NOT NULL,
    trade_date      DATE NOT NULL,
    algo_instance   VARCHAR(64) NOT NULL,
    client_name     VARCHAR(64) NOT NULL,
    symbol          VARCHAR(16) NOT NULL,
    side            VARCHAR(4) NOT NULL,
    buy_amount      DECIMAL(18,2) NOT NULL,
    sell_amount     DECIMAL(18,2) NOT NULL,
    buy_qty         BIGINT NOT NULL,
    sell_qty        BIGINT NOT NULL,
    round_trip_cnt  INT NOT NULL,
    net_pnl         DECIMAL(18,2) NOT NULL,
    return_bps      DECIMAL(12,4) NOT NULL,
    unclosed_flag   TINYINT NOT NULL,
    calc_version    BIGINT NOT NULL,
    update_time     TIMESTAMP NOT NULL,
    PRIMARY KEY (order_id, trade_date)
);

CREATE TABLE IF NOT EXISTS to_broker_order_detail (
    child_id        BIGINT NOT NULL,
    order_id        BIGINT NOT NULL,
    trade_date      DATE NOT NULL,
    side            VARCHAR(4) NOT NULL,
    exec_time       TIMESTAMP NOT NULL,
    qty             BIGINT NOT NULL,
    price           DECIMAL(12,4) NOT NULL,
    filled_qty      BIGINT NOT NULL,
    avg_fill_price  DECIMAL(12,4),
    is_cancelled    TINYINT NOT NULL,
    is_rejected     TINYINT NOT NULL,
    report_time     TIMESTAMP NOT NULL,
    PRIMARY KEY (child_id, trade_date)
);

CREATE TABLE IF NOT EXISTS compute_task (
    trade_date      DATE NOT NULL,
    shard_no        INT NOT NULL,
    status          VARCHAR(16) NOT NULL,
    owner           VARCHAR(64),
    version         BIGINT NOT NULL DEFAULT 0,
    heartbeat_time  TIMESTAMP NULL,
    retry_count     INT NOT NULL DEFAULT 0,
    update_time     TIMESTAMP NOT NULL,
    PRIMARY KEY (trade_date, shard_no)
);

CREATE TABLE IF NOT EXISTS indicator_result (
    order_id        BIGINT NOT NULL,
    trade_date      DATE NOT NULL,
    algo_instance   VARCHAR(64) NOT NULL,
    client_name     VARCHAR(64) NOT NULL,
    vwap_perf_bps   DECIMAL(12,4),
    vwap_ot_perf_bps DECIMAL(12,4),
    twap_perf_bps   DECIMAL(12,4),
    twap_ot_perf_bps DECIMAL(12,4),
    arrival_perf_bps DECIMAL(12,4),
    open_perf_bps   DECIMAL(12,4),
    close_perf_bps  DECIMAL(12,4),
    vwap_perf_pnl   DECIMAL(18,2),
    twap_perf_pnl   DECIMAL(18,2),
    arrival_perf_pnl DECIMAL(18,2),
    open_perf_pnl   DECIMAL(18,2),
    close_perf_pnl  DECIMAL(18,2),
    cancel_ratio    DECIMAL(8,4),
    reject_ratio    DECIMAL(8,4),
    fill_ratio      DECIMAL(8,4),
    participation   DECIMAL(8,4),
    impact_bps      DECIMAL(12,4),
    exec_minutes    DECIMAL(8,2),
    calc_version    BIGINT NOT NULL,
    update_time     TIMESTAMP NOT NULL,
    PRIMARY KEY (order_id, trade_date)
);

CREATE TABLE IF NOT EXISTS algo_daily_summary (
    algo_instance   VARCHAR(64) NOT NULL,
    trade_date      DATE NOT NULL,
    order_count     INT NOT NULL,
    avg_vwap_bps    DECIMAL(12,4),
    avg_twap_bps    DECIMAL(12,4),
    avg_cancel_ratio DECIMAL(8,4),
    avg_fill_ratio  DECIMAL(8,4),
    perf_score      DECIMAL(6,2),
    fill_score      DECIMAL(6,2),
    stability_score DECIMAL(6,2),
    risk_score      DECIMAL(6,2),
    impact_score    DECIMAL(6,2),
    total_score     DECIMAL(6,2),
    update_time     TIMESTAMP NOT NULL,
    PRIMARY KEY (algo_instance, trade_date)
);

CREATE TABLE IF NOT EXISTS to_day_detail (
    trade_date      DATE NOT NULL,
    algo_instance   VARCHAR(64) NOT NULL,
    parent_count    INT NOT NULL,
    child_count     INT NOT NULL,
    task_amount     DECIMAL(18,2) NOT NULL,
    open_amount     DECIMAL(18,2) NOT NULL,
    net_profit      DECIMAL(18,2) NOT NULL,
    day_return_bps  DECIMAL(12,4) NOT NULL,
    win_rate        DECIMAL(8,4),
    sharpe          DECIMAL(10,4),
    max_drawdown_bps DECIMAL(12,4),
    calmar          DECIMAL(10,4),
    open_ratio      DECIMAL(8,4),
    unclosed_cnt    INT NOT NULL,
    cancel_ratio    DECIMAL(8,4),
    reject_ratio    DECIMAL(8,4),
    update_time     TIMESTAMP NOT NULL,
    PRIMARY KEY (algo_instance, trade_date)
);

CREATE TABLE IF NOT EXISTS industry_baseline (
    trade_date      DATE NOT NULL,
    dimension       VARCHAR(16) NOT NULL,
    industry_avg    DECIMAL(10,4) NOT NULL,
    sample_count    INT NOT NULL,
    update_time     TIMESTAMP NOT NULL,
    PRIMARY KEY (trade_date, dimension)
);
