package com.algoeval.source.sdk;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行情 tick 事件（不可变）。
 *
 * @param symbol   标的代码
 * @param tickTime 行情时间（模拟市场时钟）
 * @param price    最新价
 * @param volume   该 tick 成交量（股）
 * @param turnover 该 tick 成交额（元）
 */
public record TickEvent(String symbol,
                        LocalDateTime tickTime,
                        BigDecimal price,
                        long volume,
                        BigDecimal turnover) {
}
