package com.algoeval.collector.hq.observer;

import com.algoeval.collector.hq.disruptor.TickEventWrapper;
import com.algoeval.source.sdk.MarketDataCache;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 缓存观察者：刷新最新价缓存，供实时计算与算法引擎读取。
 */
@Component
public class CacheObserver implements HqTickObserver {

    private final MarketDataCache cache;

    public CacheObserver(MarketDataCache cache) {
        this.cache = cache;
    }

    @Override
    public String name() {
        return "cache";
    }

    @Override
    public void onBatch(List<TickEventWrapper> batch) {
        for (TickEventWrapper tick : batch) {
            cache.onTick(tick.toTickEvent());
        }
    }
}
