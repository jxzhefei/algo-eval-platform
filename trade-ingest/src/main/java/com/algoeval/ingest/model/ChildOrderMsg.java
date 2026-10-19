package com.algoeval.ingest.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 子单消息（算法拆出的委托/回报）。含买卖方向与委托价（T0 回转双腿），
 * cancelled=撤单、rejected=废单（流动性不足等），二者口径独立。
 */
public record ChildOrderMsg(long childId,
                            long orderId,
                            String side,
                            LocalDateTime execTime,
                            long qty,
                            BigDecimal price,
                            long filledQty,
                            BigDecimal avgFillPrice,
                            boolean cancelled,
                            boolean rejected) {
}
