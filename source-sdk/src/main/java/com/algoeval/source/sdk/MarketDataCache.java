package com.algoeval.source.sdk;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最新价缓存：采集侧 CacheObserver 写入，实时计算/算法引擎读取。
 * 行情链路共享组件，属于 SDK 契约的一部分。
 */
public class MarketDataCache {

    private final Map<String, TickEvent> latestTick = new ConcurrentHashMap<>();

    public void onTick(TickEvent tick) {
        latestTick.put(tick.symbol(), tick);
    }

    public TickEvent latest(String symbol) {
        return latestTick.get(symbol);
    }

    public Collection<TickEvent> snapshot() {
        return latestTick.values();
    }

    public int size() {
        return latestTick.size();
    }
}
