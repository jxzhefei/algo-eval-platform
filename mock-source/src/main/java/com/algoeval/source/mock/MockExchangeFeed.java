package com.algoeval.source.mock;

import com.algoeval.source.sdk.QuoteCurveProvider;
import com.algoeval.source.sdk.QuoteListCodec;
import com.algoeval.source.sdk.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模拟行情前置机（JeroMQ PUB 端，扮演交易所快照源）：
 * 按市场分钟节奏对外发布 Protobuf QuoteList 批量帧，
 * collector-hq 的 JeromqHqSource（SUB）订阅后进入 Disruptor 管道——
 * 模拟链路与生产链路共用同一条传输契约（TCP/JeroMQ + Protobuf）。
 * 同时实现 QuoteCurveProvider：模拟交易终端（collector-trade）据此生成与行情流自洽的委托。
 */
public class MockExchangeFeed implements QuoteCurveProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MockExchangeFeed.class);

    private final MockExchangeSource curveSource = new MockExchangeSource();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ZContext context;
    private volatile ScheduledExecutorService executor;

    private String endpoint = "tcp://127.0.0.1:5570";
    private long millisPerMinute = 1000L;
    private int symbolCount = 100;
    private long seed = 42L;
    private LocalDate tradeDate;

    public void configure(Map<String, String> props) {
        this.symbolCount = Integer.parseInt(props.getOrDefault("symbols", "100"));
        this.seed = Long.parseLong(props.getOrDefault("seed", "42"));
        this.millisPerMinute = Long.parseLong(props.getOrDefault("millisPerMinute", "1000"));
        this.endpoint = props.getOrDefault("endpoint", endpoint);
    }

    /** 绑定 PUB 端点并按市场分钟节奏发布当日行情帧 */
    public synchronized void start(LocalDate date) {
        if (running.compareAndSet(false, true)) {
            this.tradeDate = date;
            curveSource.configure(Map.of(
                    "symbols", String.valueOf(symbolCount),
                    "seed", String.valueOf(seed),
                    "millisPerMinute", String.valueOf(millisPerMinute)));
            curveSource.prepareDay(date);

            context = new ZContext();
            ZMQ.Socket pub = context.createSocket(SocketType.PUB);
            pub.setSndHWM(1_000_000);
            pub.bind(endpoint);
            log.info("[mock-feed] 行情前置 PUB 已绑定 {}（{} 标的 × 240 分钟，节奏 {}ms/分钟）",
                    endpoint, symbolCount, millisPerMinute);

            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mock-exchange-feed");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(new Runnable() {
                private int minute = 0;
                private int seq = 0;

                @Override
                public void run() {
                    if (!running.get() || minute >= MockExchangeSource.MARKET_MINUTES) {
                        return;
                    }
                    List<TickEvent> frame = new ArrayList<>(symbolCount);
                    for (String symbol : curveSource.symbols(date)) {
                        List<TickEvent> curve = curveSource.dayCurve(date, symbol);
                        if (curve.size() > minute) {
                            frame.add(curve.get(minute));
                        }
                    }
                    if (!frame.isEmpty()) {
                        pub.send(QuoteListCodec.encode(frame, ++seq, 0));
                    }
                    minute++;
                    if (minute >= MockExchangeSource.MARKET_MINUTES) {
                        log.info("[mock-feed] 交易日 {} 行情发布完毕（共 {} 分钟）", date, minute);
                    }
                }
            }, millisPerMinute, millisPerMinute, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
        if (context != null) {
            context.close();
        }
    }

    // ══ QuoteCurveProvider：模拟交易终端的"未来曲线"特权（与发布的行情流同源）═══

    @Override
    public List<TickEvent> dayCurve(LocalDate date, String symbol) {
        return curveSource.dayCurve(date, symbol);
    }

    @Override
    public List<String> symbols(LocalDate date) {
        return curveSource.symbols(date);
    }

    @Override
    public int marketMinutes() {
        return curveSource.marketMinutes();
    }
}
