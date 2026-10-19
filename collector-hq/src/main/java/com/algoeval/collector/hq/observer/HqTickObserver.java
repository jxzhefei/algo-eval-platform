package com.algoeval.collector.hq.observer;

import com.algoeval.collector.hq.disruptor.TickEventWrapper;

import java.util.List;

/**
 * 行情观察者：Disruptor 攒批后由 HqSubject 并行分发。
 * 典型实现：KafkaPubObserver（发消息）、MonitorObserver（延迟分位数）、CacheObserver（最新价缓存）。
 */
public interface HqTickObserver {

    String name();

    /** 批量消费一批 tick（攒批语义：一次调用处理一批快照，摊薄系统调用开销） */
    void onBatch(List<TickEventWrapper> batch);
}
