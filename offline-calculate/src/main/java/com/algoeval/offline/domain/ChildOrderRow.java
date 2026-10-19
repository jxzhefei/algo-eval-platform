package com.algoeval.offline.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 子单表行（含方向与委托价；cancelled/rejected 口径独立） */
public record ChildOrderRow(long childId,
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
