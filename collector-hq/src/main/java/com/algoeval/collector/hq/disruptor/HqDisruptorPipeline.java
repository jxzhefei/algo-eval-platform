package com.algoeval.collector.hq.disruptor;

import com.algoeval.collector.hq.observer.HqTickObserver;
import com.algoeval.source.sdk.TickEvent;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Disruptor 行情处理管道：
 * <ul>
 *   <li>RingBuffer 1024 槽位，预分配事件对象，零 GC 压力</li>
 *   <li>单生产者（行情源线程）+ CAS 无锁发布</li>
 *   <li>攒批消费：满 64 条或批次结束（endOfBatch）即分发，摊薄系统调用开销</li>
 * </ul>
 */
@Component
public class HqDisruptorPipeline {

    private static final Logger log = LoggerFactory.getLogger(HqDisruptorPipeline.class);
    private static final int RING_BUFFER_SIZE = 1024;   // 必须为 2 的幂
    private static final int BATCH_SIZE = 64;

    private final List<HqTickObserver> observers;
    private Disruptor<TickEventWrapper> disruptor;
    private RingBuffer<TickEventWrapper> ringBuffer;

    public HqDisruptorPipeline(List<HqTickObserver> observers) {
        this.observers = observers;
    }

    public synchronized void start() {
        if (disruptor != null) {
            return;
        }
        disruptor = new Disruptor<>(TickEventWrapper::new, RING_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, new SleepingWaitStrategy());
        disruptor.handleEventsWith(new BatchDispatchHandler());
        disruptor.start();
        ringBuffer = disruptor.getRingBuffer();
        log.info("[collector-hq] Disruptor 启动：{} 槽位，攒批 {} 条，观察者 {}",
                RING_BUFFER_SIZE, BATCH_SIZE, observers.stream().map(HqTickObserver::name).toList());
    }

    /** 行情源线程调用：CAS 无锁发布，队列满时自旋等待（行情不丢） */
    public void publish(TickEvent tick) {
        ringBuffer.publishEvent((event, sequence, src) -> event.copyFrom(src), tick);
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (disruptor != null) {
            disruptor.shutdown();
            disruptor = null;
        }
    }

    /**
     * 攒批事件处理器：积累到 BATCH_SIZE 或 endOfBatch 时把快照分发给全部观察者。
     */
    private class BatchDispatchHandler implements EventHandler<TickEventWrapper> {

        private final List<TickEventWrapper> batch = new ArrayList<>(BATCH_SIZE);

        @Override
        public void onEvent(TickEventWrapper event, long sequence, boolean endOfBatch) {
            batch.add(snapshot(event));
            if (batch.size() >= BATCH_SIZE || endOfBatch) {
                List<TickEventWrapper> toDispatch = List.copyOf(batch);
                batch.clear();
                for (HqTickObserver observer : observers) {
                    try {
                        observer.onBatch(toDispatch);
                    } catch (Exception e) {
                        log.error("[collector-hq] 观察者 {} 处理批次失败", observer.name(), e);
                    }
                }
            }
        }

        /** 复制快照：槽位对象会被后续事件覆写，观察者消费前必须拷贝 */
        private TickEventWrapper snapshot(TickEventWrapper src) {
            TickEventWrapper copy = new TickEventWrapper();
            copy.copyFromWrapper(src);
            return copy;
        }
    }
}
