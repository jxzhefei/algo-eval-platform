package com.algoeval.collector.hq.source.jeromq;

import com.algoeval.source.sdk.HqSource;
import com.algoeval.source.sdk.QuoteListCodec;
import com.algoeval.source.sdk.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * JeroMQ 行情源（对应真实系统 collector-hq-source-jeromq / JeromqHqSourceImpl）：
 * SUB 订阅行情前置机 PUB 端点，接收 Protobuf QuoteList 批量行情帧，
 * 逐条投递 Disruptor RingBuffer（由管道攒批）。
 * 订阅端高水位上限调高（行情快照批小频低，正常水位远低于上限）。
 */
public class JeromqHqSource implements HqSource {

    private static final Logger log = LoggerFactory.getLogger(JeromqHqSource.class);

    private String endpoint = "tcp://127.0.0.1:5570";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong received = new AtomicLong();
    private volatile ZContext context;
    private volatile Thread worker;

    @Override
    public String name() {
        return "jeromq";
    }

    @Override
    public void configure(Map<String, String> props) {
        String ep = props.get("endpoint");
        if (ep != null && !ep.isBlank()) {
            this.endpoint = ep;
        }
    }

    @Override
    public void start(LocalDate tradeDate, Consumer<TickEvent> consumer) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        context = new ZContext();
        worker = new Thread(() -> {
            try (ZMQ.Socket sub = context.createSocket(SocketType.SUB)) {
                sub.setRcvHWM(1_000_000);
                sub.connect(endpoint);
                sub.subscribe(new byte[0]);
                log.info("[jeromq-source] SUB 已连接行情前置 PUB: {}", endpoint);
                byte[] frame;
                while (running.get() && (frame = sub.recv()) != null) {
                    try {
                        List<TickEvent> ticks = QuoteListCodec.decode(frame);
                        for (TickEvent tick : ticks) {
                            consumer.accept(tick);
                        }
                        long total = received.addAndGet(ticks.size());
                        if (total % 100_000 < ticks.size()) {
                            log.info("[jeromq-source] 已累计接收行情 {} 条", total);
                        }
                    } catch (Exception e) {
                        log.warn("[jeromq-source] 帧解码失败（跳过，不阻塞管道）: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.error("[jeromq-source] 订阅异常退出: {}", e.getMessage(), e);
                }
            }
        }, "jeromq-hq-source");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        running.set(false);
        if (context != null) {
            context.close();
        }
    }
}
