package com.algoeval.collector.hq.disruptor;

import com.algoeval.source.sdk.TickEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RingBuffer 预分配的可变事件对象——零 GC 压力的关键：
 * 槽位对象在启动时一次性创建，运行期只改字段不建新对象。
 */
public class TickEventWrapper {

    private String symbol;
    private LocalDateTime tickTime;
    private BigDecimal price;
    private long volume;
    private BigDecimal turnover;
    /** 进入 RingBuffer 的真实时间戳（纳秒），用于端到端延迟分位数统计 */
    private long ingestNanos;

    public void copyFrom(TickEvent src) {
        this.symbol = src.symbol();
        this.tickTime = src.tickTime();
        this.price = src.price();
        this.volume = src.volume();
        this.turnover = src.turnover();
        this.ingestNanos = System.nanoTime();
    }

    /** 槽位快照拷贝（保留 ingestNanos 供延迟统计） */
    public void copyFromWrapper(TickEventWrapper src) {
        this.symbol = src.symbol;
        this.tickTime = src.tickTime;
        this.price = src.price;
        this.volume = src.volume;
        this.turnover = src.turnover;
        this.ingestNanos = src.ingestNanos;
    }

    public TickEvent toTickEvent() {
        return new TickEvent(symbol, tickTime, price, volume, turnover);
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDateTime getTickTime() {
        return tickTime;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getVolume() {
        return volume;
    }

    public BigDecimal getTurnover() {
        return turnover;
    }

    public long getIngestNanos() {
        return ingestNanos;
    }
}
