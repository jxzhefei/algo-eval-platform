package com.algoeval.ingest.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 母单消息（算法交易任务）——接入层归一化后的平台统一模型。
 * 业务字段对齐设计文档 §6.5 parent_order：
 * 委托时间（startTime/endTime）供"委托时间基准"绩效口径使用。
 */
public record ParentOrderMsg(long orderId,
                             String algoInstance,
                             String algoType,
                             String algoVendor,
                             String clientName,
                             String accountId,
                             String symbol,
                             LocalDate tradeDate,
                             String side,
                             long taskQty,
                             BigDecimal taskAmount,
                             long filledQty,
                             BigDecimal filledAmount,
                             BigDecimal avgFillPrice,
                             BigDecimal arrivalPrice,
                             LocalDateTime startTime,
                             LocalDateTime endTime,
                             String status) {
}
