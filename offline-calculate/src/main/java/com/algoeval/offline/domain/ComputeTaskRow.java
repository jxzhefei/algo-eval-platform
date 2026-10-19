package com.algoeval.offline.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 计算任务表行：一行 = 一个交易日的一个逻辑分片 */
public record ComputeTaskRow(LocalDate tradeDate,
                             int shardNo,
                             String status,
                             String owner,
                             long version,
                             LocalDateTime heartbeatTime,
                             int retryCount) {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
}
