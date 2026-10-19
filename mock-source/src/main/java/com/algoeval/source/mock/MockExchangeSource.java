package com.algoeval.source.mock;

import com.algoeval.source.sdk.HqSource;
import com.algoeval.source.sdk.QuoteCurveProvider;
import com.algoeval.source.sdk.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 内置行情生成器（模拟上交所/深交所行情流）：
 * <ul>
 *   <li>100 只标的（可配），GBM 随机游走价格（日波动率 2%，每标的随机日内漂移）</li>
 *   <li>成交量日内 U 型分布：开盘/收盘放量，午间缩量——真实市场形态</li>
 *   <li>交易时段 09:30-11:30 / 13:00-15:00，共 240 个市场分钟</li>
 *   <li>演示节奏可配：默认 1 秒真实时间 = 1 分钟行情时间，一个交易日 4 分钟演完</li>
 *   <li>固定种子可复现：同参数两次运行产生完全一致的行情</li>
 * </ul>
 */
public class MockExchangeSource implements HqSource, QuoteCurveProvider {

    private static final Logger log = LoggerFactory.getLogger(MockExchangeSource.class);

    public static final int MARKET_MINUTES = 240;
    private static final LocalTime MORNING_OPEN = LocalTime.of(9, 30);
    private static final LocalTime AFTERNOON_OPEN = LocalTime.of(13, 0);
    private static final int MORNING_MINUTES = 120;

    private int symbolCount = 100;
    private long seed = 42L;
    /** 真实毫秒数 / 市场分钟，默认 1000ms = 1 市场分钟 */
    private long millisPerMinute = 1000L;

    private final Map<String, List<TickEvent>> curves = new ConcurrentHashMap<>();
    private final List<String> symbolList = new ArrayList<>();
    private volatile ScheduledExecutorService executor;
    private volatile boolean running = false;
    private volatile LocalDate currentDate;

    @Override
    public String name() {
        return "mock-exchange";
    }

    @Override
    public void configure(Map<String, String> props) {
        this.symbolCount = Integer.parseInt(props.getOrDefault("symbols", "100"));
        this.seed = Long.parseLong(props.getOrDefault("seed", "42"));
        this.millisPerMinute = Long.parseLong(props.getOrDefault("millisPerMinute", "1000"));
    }

    @Override
    public synchronized void start(LocalDate tradeDate, Consumer<TickEvent> consumer) {
        if (running) {
            return;
        }
        this.currentDate = tradeDate;
        generateDay(tradeDate);
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mock-exchange-source");
            t.setDaemon(true);
            return t;
        });
        // 每个"市场分钟"（默认 1 真实秒）推送一轮全部标的的 tick
        executor.scheduleAtFixedRate(new Runnable() {
            private int minute = 0;

            @Override
            public void run() {
                if (!running || minute >= MARKET_MINUTES) {
                    return;
                }
                for (String symbol : symbolList) {
                    consumer.accept(curves.get(symbol).get(minute));
                }
                minute++;
                if (minute >= MARKET_MINUTES) {
                    log.info("[mock-source] 交易日 {} 行情推送完毕（{} 标的 × {} 分钟）",
                            tradeDate, symbolList.size(), MARKET_MINUTES);
                }
            }
        }, 0, millisPerMinute, TimeUnit.MILLISECONDS);
        log.info("[mock-source] 行情源启动：{} 标的，节奏 {}ms/市场分钟，种子 {}", symbolCount, millisPerMinute, seed);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * 预生成当日全部标的的 240 分钟行情曲线（确定性，固定种子）。
     */
    private void generateDay(LocalDate tradeDate) {
        curves.clear();
        symbolList.clear();
        Random dayRandom = new Random(seed + tradeDate.toEpochDay());
        for (int i = 0; i < symbolCount; i++) {
            String symbol = String.format("%06d.%s", 600000 + i, i % 2 == 0 ? "SH" : "SZ");
            symbolList.add(symbol);
            curves.put(symbol, generateSymbolCurve(tradeDate, symbol, new Random(dayRandom.nextLong())));
        }
    }

    /**
     * 单标的日内曲线：GBM 随机游走（年化波动折算到分钟），U 型成交量。
     */
    private List<TickEvent> generateSymbolCurve(LocalDate tradeDate, String symbol, Random random) {
        List<TickEvent> ticks = new ArrayList<>(MARKET_MINUTES);
        // 初始价 5~100 元；日内漂移 [-1.5%, +1.5%]；日波动率 2% → 分钟波动 2%/sqrt(240)
        double price = 5 + random.nextDouble() * 95;
        double driftPerMinute = (random.nextDouble() - 0.5) * 0.03 / MARKET_MINUTES;
        double minuteVol = 0.02 / Math.sqrt(MARKET_MINUTES);
        // 该标的当日基准成交量：每分钟 2万~200万股
        double baseVolume = 20_000 + random.nextDouble() * 1_980_000;

        for (int m = 0; m < MARKET_MINUTES; m++) {
            double shock = random.nextGaussian() * minuteVol;
            price = Math.max(0.5, price * Math.exp(driftPerMinute - minuteVol * minuteVol / 2 + shock));
            double uShape = uShapeWeight(m);
            long volume = Math.max(1000L, Math.round(baseVolume * uShape * (0.7 + random.nextDouble() * 0.6)));
            BigDecimal px = BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP);
            BigDecimal turnover = px.multiply(BigDecimal.valueOf(volume)).setScale(2, RoundingMode.HALF_UP);
            ticks.add(new TickEvent(symbol, marketTime(tradeDate, m), px, volume, turnover));
        }
        return ticks;
    }

    /**
     * U 型分布权重：开盘/收盘约为午间 3 倍。
     */
    static double uShapeWeight(int minute) {
        double x = (double) minute / (MARKET_MINUTES - 1); // 0..1
        return 1.0 + 2.0 * Math.pow(2 * x - 1, 2);         // 端点 3.0，中点 1.0
    }

    /** 市场分钟序号 → 模拟行情时间（09:30-11:30 / 13:00-15:00） */
    public static LocalDateTime marketTime(LocalDate tradeDate, int minute) {
        if (minute < MORNING_MINUTES) {
            return LocalDateTime.of(tradeDate, MORNING_OPEN).plusMinutes(minute);
        }
        return LocalDateTime.of(tradeDate, AFTERNOON_OPEN).plusMinutes(minute - MORNING_MINUTES);
    }

    /** 预生成当日行情曲线（幂等）：供 JeroMQ 前置（MockExchangeFeed）复用同一曲线生成能力 */
    public synchronized void prepareDay(LocalDate tradeDate) {
        if (tradeDate.equals(currentDate) && !curves.isEmpty()) {
            return;
        }
        this.currentDate = tradeDate;
        generateDay(tradeDate);
    }

    @Override
    public List<TickEvent> dayCurve(LocalDate tradeDate, String symbol) {
        if (!tradeDate.equals(currentDate)) {
            return List.of();
        }
        return curves.getOrDefault(symbol, List.of());
    }

    @Override
    public List<String> symbols(LocalDate tradeDate) {
        return tradeDate.equals(currentDate) ? List.copyOf(symbolList) : List.of();
    }

    @Override
    public int marketMinutes() {
        return MARKET_MINUTES;
    }
}
