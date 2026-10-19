package com.algoeval.collector.hq.observer;

import com.algoeval.collector.hq.disruptor.TickEventWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 监控观察者：端到端延迟分位数统计（P50/P99）。
 * 采样蓄水池保留最近 4096 个样本，每 2 万条输出一次分位数。
 */
@Component
public class MonitorObserver implements HqTickObserver {

    private static final Logger log = LoggerFactory.getLogger(MonitorObserver.class);
    private static final int RESERVOIR_SIZE = 4096;

    private final ConcurrentLinkedDeque<Double> latenciesMs = new ConcurrentLinkedDeque<>();
    private long processed = 0;

    @Override
    public String name() {
        return "monitor";
    }

    @Override
    public void onBatch(List<TickEventWrapper> batch) {
        long now = System.nanoTime();
        for (TickEventWrapper tick : batch) {
            latenciesMs.add((now - tick.getIngestNanos()) / 1_000_000.0);
            if (latenciesMs.size() > RESERVOIR_SIZE) {
                latenciesMs.pollFirst();
            }
        }
        processed += batch.size();
        if (processed % 20_000 < batch.size() && !latenciesMs.isEmpty()) {
            List<Double> sorted = new ArrayList<>(latenciesMs);
            java.util.Collections.sort(sorted);
            log.info("[collector-hq] 行情链路延迟 P50={}ms P99={}ms（累计 {} 条）",
                    pct(sorted, 0.50), pct(sorted, 0.99), processed);
        }
    }

    private static String pct(List<Double> sorted, double q) {
        int idx = Math.min(sorted.size() - 1, (int) (sorted.size() * q));
        return String.format("%.2f", sorted.get(idx));
    }
}
