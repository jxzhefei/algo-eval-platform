package com.algoeval.collector.trade.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 母单草稿（拆单前的完整上下文）。
 * startMinute/endMinute 为委托时间窗（"委托时间基准"绩效口径的区间）；
 * thirdParty=true 时经第三方算法商通道（异构报文）上报。
 */
public record ParentOrderDraft(long orderId,
                               String algoInstance,
                               String algoType,
                               String algoVendor,
                               String clientName,
                               String accountId,
                               String symbol,
                               LocalDate tradeDate,
                               String side,
                               BigDecimal taskAmount,
                               BigDecimal arrivalPrice,
                               int arrivalMinute,
                               long totalQty,
                               int startMinute,
                               int endMinute,
                               boolean thirdParty) {
}
