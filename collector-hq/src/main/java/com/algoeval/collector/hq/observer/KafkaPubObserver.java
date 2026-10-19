package com.algoeval.collector.hq.observer;

import com.algoeval.collector.hq.disruptor.TickEventWrapper;
import com.algoeval.infra.leader.LeaderElector;
import com.algoeval.source.sdk.QuoteListCodec;
import com.algoeval.source.sdk.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka 发布观察者（对齐真实系统 HqKafkaPubObserverImpl）：
 * <ul>
 *   <li>批内行情按交易日聚组为 Protobuf Quote.QuoteList，一帧发布——批量序列化摊薄开销</li>
 *   <li>选主门控：非主节点不分发（多节点部署防重复发布，§4.4/§13.6）</li>
 * </ul>
 */
@Component
public class KafkaPubObserver implements HqTickObserver {

    private static final Logger log = LoggerFactory.getLogger(KafkaPubObserver.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final LeaderElector leaderElector;
    private final String topic;
    private final AtomicInteger batchSeq = new AtomicInteger();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong skippedAsStandby = new AtomicLong();

    public KafkaPubObserver(KafkaTemplate<String, byte[]> kafkaTemplate,
                            LeaderElector leaderElector,
                            @Value("${algo.kafka.topic.market:market-tick}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.leaderElector = leaderElector;
        this.topic = topic;
    }

    @Override
    public String name() {
        return "kafka-pub";
    }

    @Override
    public void onBatch(List<TickEventWrapper> batch) {
        if (!leaderElector.isLeader()) {
            // 备节点：跳过分发（本地缓存/监控观察者不受影响），等待主节点租约失效接管
            long skipped = skippedAsStandby.addAndGet(batch.size());
            if (skipped % 50_000 < batch.size()) {
                log.debug("[collector-hq] 备节点累计跳过分发 {} 条（等待选主接管）", skipped);
            }
            return;
        }
        List<TickEvent> ticks = new ArrayList<>(batch.size());
        for (TickEventWrapper wrapper : batch) {
            ticks.add(wrapper.toTickEvent());
        }
        kafkaTemplate.send(topic, "hq",
                QuoteListCodec.encode(ticks, batchSeq.incrementAndGet(), 0));
        long total = published.addAndGet(ticks.size());
        if (total % 50_000 < ticks.size()) {
            log.info("[collector-hq] 已累计发布行情 {} 条 → topic {}", total, topic);
        }
    }

    public long publishedCount() {
        return published.get();
    }
}
