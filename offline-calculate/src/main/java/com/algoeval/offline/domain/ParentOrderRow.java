package com.algoeval.offline.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 母单表行（字段对齐设计文档 §6.5 parent_order） */
public record ParentOrderRow(long orderId,
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
