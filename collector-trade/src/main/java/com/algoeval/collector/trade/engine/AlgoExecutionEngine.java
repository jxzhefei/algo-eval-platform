package com.algoeval.collector.trade.engine;

import com.algoeval.ingest.ThirdPartyNormalizer;
import com.algoeval.ingest.model.ChildOrderMsg;
import com.algoeval.ingest.model.ParentOrderMsg;
import com.algoeval.source.sdk.QuoteCurveProvider;
import com.algoeval.source.sdk.TickEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟交易终端：生成母单并按算法特征拆成子单，经交易接入层（REST）上报。
 * <p>
 * 供应商口径（对齐真实系统）：模拟算法均为我方自研算法（kf_ 前缀，算法商=KAFANG），
 * 测试环境算法商总数=1；第三方算法商通道经接入层归一化（/third-party），默认关闭
 * （algo.sim.thirdparty.enabled 开启后 10% 母单走第三方异构报文）。
 * <p>
 * 行为差异来自拆单逻辑（时间切片 × 冲击模型），绩效差异因此真实：
 * TWAP 均匀切片午间冲击大；VWAP 量加权贴近市场 VWAP；T0 双腿回转高抛低吸。
 * 母单生命周期真实化：到达时刻报 RUNNING（零成交）→ 逐分钟子单回报 → 末腿后报 FINISHED（终态）。
 */
@Service
public class AlgoExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(AlgoExecutionEngine.class);

    private static final String[] ALGO_TWAP = {"kf_twap_plus"};
    private static final String[] ALGO_VWAP = {"kf_vwap_plus"};
    private static final String[] ALGO_T0 = {"kf_t0"};
    private static final String VENDOR = "KAFANG";
    private static final int CLIENT_COUNT = 10;

    private final ConfigurableApplicationContext context;
    private final TradeTerminalClient terminal;

    @Value("${algo.sim.orders:3000}")
    private int orderCount;
    @Value("${algo.sim.seed:42}")
    private long seed;
    @Value("${algo.sim.millis-per-minute:1000}")
    private long millisPerMinute;
    @Value("${algo.sim.thirdparty.enabled:false}")
    private boolean thirdPartyEnabled;

    private final AtomicLong childIdSeq = new AtomicLong(1);
    private volatile ScheduledExecutorService executor;
    private volatile boolean running = false;

    public AlgoExecutionEngine(ConfigurableApplicationContext context, TradeTerminalClient terminal) {
        this.context = context;
        this.terminal = terminal;
    }

    /** 生成当日全部母单+子单计划，并按市场分钟经接入层上报。 */
    public synchronized void start(LocalDate tradeDate) {
        if (running) {
            return;
        }
        QuoteCurveProvider curveProvider = (QuoteCurveProvider) context.getBean("quoteCurveProvider");
        List<String> symbols = curveProvider.symbols(tradeDate);
        int marketMinutes = curveProvider.marketMinutes();

        Map<Integer, List<Runnable>> emitPlan = new ConcurrentSkipListMap<>();
        Random random = new Random(seed * 31 + tradeDate.toEpochDay());
        long totalChildren = 0;
        for (long orderId = 1; orderId <= orderCount; orderId++) {
            ParentOrderDraft draft = randomDraft(orderId, tradeDate, symbols, marketMinutes, random);
            List<TickEvent> curve = curveProvider.dayCurve(tradeDate, draft.symbol());
            if (curve.size() < marketMinutes) {
                continue;
            }
            // 回填 arrival 价与母单总量（股）
            BigDecimal arrivalPrice = curve.get(draft.arrivalMinute()).price();
            draft = withQty(draft, arrivalPrice, curve, random);

            List<ChildOrderMsg> children = executeOrder(draft, curve, random);
            totalChildren += children.size();
            emit(draft, children, curve, emitPlan);
        }

        running = true;
        long finalTotalChildren = totalChildren;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "algo-execution-engine");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(new Runnable() {
            private int minute = 0;

            @Override
            public void run() {
                if (!running || minute >= marketMinutes) {
                    return;
                }
                List<Runnable> batch = emitPlan.get(minute);
                if (batch != null) {
                    batch.forEach(Runnable::run);
                }
                minute++;
            }
        }, millisPerMinute / 2, millisPerMinute, TimeUnit.MILLISECONDS);

        log.info("[collector-trade] 模拟交易终端启动：{} 母单 / {} 子单，交易日 {}，经 REST 接入层上报",
                orderCount, finalTotalChildren, tradeDate);
    }

    /** 母单生命周期编排：到达报 RUNNING → 子单逐分钟回报 → 末腿后报 FINISHED */
    private void emit(ParentOrderDraft draft, List<ChildOrderMsg> children,
                      List<TickEvent> curve, Map<Integer, List<Runnable>> emitPlan) {
        OrderRef ref = new OrderRef(draft.orderId());
        int lastMinute = draft.arrivalMinute();

        if (draft.thirdParty()) {
            // 第三方算法商：异构报文经接入层归一化（平台母单号由接入层分配）
            emitPlan.computeIfAbsent(draft.arrivalMinute(), k -> new ArrayList<>()).add(() -> {
                long normalizedId = terminal.postThirdParty(toThirdParty(draft));
                if (normalizedId > 0) {
                    ref.orderId = normalizedId;
                }
            });
        } else {
            ParentOrderMsg initial = toParentMsg(draft, ref.orderId, BigDecimal.ZERO, 0, null, "RUNNING");
            emitPlan.computeIfAbsent(draft.arrivalMinute(), k -> new ArrayList<>())
                    .add(() -> terminal.postParent(initial));
        }

        // 子单按执行分钟分批回报（同分钟合并一个批量上报）
        TreeMap<Integer, List<ChildOrderMsg>> byMinute = new TreeMap<>();
        for (ChildOrderMsg child : children) {
            int minute = minuteOf(child.execTime(), curve.get(0).tickTime());
            lastMinute = Math.max(lastMinute, minute);
            byMinute.computeIfAbsent(minute, k -> new ArrayList<>()).add(child);
        }
        byMinute.forEach((minute, batch) -> emitPlan
                .computeIfAbsent(minute, k -> new ArrayList<>())
                .add(() -> terminal.postChildren(remapIds(batch, ref))));

        // 终态母单（终态成交汇总）
        BigDecimal filled = BigDecimal.ZERO;
        long filledQty = 0;
        for (ChildOrderMsg c : children) {
            if (c.filledQty() > 0 && c.avgFillPrice() != null) {
                filled = filled.add(c.avgFillPrice().multiply(BigDecimal.valueOf(c.filledQty())));
                filledQty += c.filledQty();
            }
        }
        BigDecimal finalFilled = filled.setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalAvg = filledQty == 0 ? null
                : finalFilled.divide(BigDecimal.valueOf(filledQty), 4, RoundingMode.HALF_UP);
        long finalQty = filledQty;
        ParentOrderMsg finished = toParentMsg(draft, 0, finalFilled, finalQty, finalAvg, "FINISHED");
        int finalMinute = lastMinute;
        emitPlan.computeIfAbsent(finalMinute, k -> new ArrayList<>()).add(() -> {
            ParentOrderMsg msg = new ParentOrderMsg(ref.orderId, finished.algoInstance(),
                    finished.algoType(), finished.algoVendor(), finished.clientName(), finished.accountId(),
                    finished.symbol(), finished.tradeDate(), finished.side(), finished.taskQty(),
                    finished.taskAmount(), finalQty, finalFilled, finalAvg, finished.arrivalPrice(),
                    finished.startTime(), finished.endTime(), "FINISHED");
            terminal.postParent(msg);
        });
    }

    private List<ChildOrderMsg> remapIds(List<ChildOrderMsg> batch, OrderRef ref) {
        List<ChildOrderMsg> mapped = new ArrayList<>(batch.size());
        for (ChildOrderMsg c : batch) {
            mapped.add(new ChildOrderMsg(c.childId(), ref.orderId, c.side(), c.execTime(), c.qty(),
                    c.price(), c.filledQty(), c.avgFillPrice(), c.cancelled(), c.rejected()));
        }
        return mapped;
    }

    /** 执行一个母单：按算法拆单并模拟成交（含撤单/废单行为）。 */
    private List<ChildOrderMsg> executeOrder(ParentOrderDraft draft, List<TickEvent> curve, Random random) {
        SlicingStrategy slicer = slicerOf(draft.algoInstance());
        int childCount = 5 + random.nextInt(26); // 5~30
        List<ChildSpec> specs = slicer.slice(draft, curve, childCount, random);
        double cancelRatio = cancelRatioOf(draft.algoInstance());

        List<ChildOrderMsg> children = new ArrayList<>(specs.size());
        for (ChildSpec spec : specs) {
            TickEvent tick = curve.get(spec.execMinute());
            boolean cancelled = random.nextDouble() < cancelRatio;
            boolean rejected = !cancelled && random.nextDouble() < 0.01; // 流动性不足废单 ~1%
            long filledQty = (cancelled || rejected) ? 0 : spec.qty();
            BigDecimal fillPrice = null;
            BigDecimal orderPrice = tick.price();
            if (filledQty > 0) {
                fillPrice = impactFillPrice(draft, tick, spec, random);
            }
            children.add(new ChildOrderMsg(childIdSeq.getAndIncrement(), draft.orderId(), spec.side(),
                    tick.tickTime(), spec.qty(), orderPrice, filledQty, fillPrice, cancelled, rejected));
        }
        return children;
    }

    /**
     * 冲击成交模型：价格 = tick价 × (1 ± impact)。
     * BUY 付冲击（更贵），SELL 收冲击（更便宜）；T0 低吸高抛获价格优势（负冲击）。
     */
    private BigDecimal impactFillPrice(ParentOrderDraft draft, TickEvent tick, ChildSpec spec, Random random) {
        double impactBps;
        if (draft.algoType().equals("T0")) {
            impactBps = -(5 + random.nextDouble() * 10); // 低吸/高抛的价格优势
        } else {
            impactBps = Math.min(60.0, 0.02 * spec.qty() / Math.max(1, tick.volume()) * 10_000);
        }
        double signed = "BUY".equals(spec.side()) ? impactBps : -impactBps;
        double price = tick.price().doubleValue() * (1 + signed / 10_000);
        return BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP);
    }

    private ParentOrderMsg toParentMsg(ParentOrderDraft d, long orderId, BigDecimal filledAmount,
                                       long filledQty, BigDecimal avgFillPrice, String status) {
        return new ParentOrderMsg(orderId > 0 ? orderId : d.orderId(), d.algoInstance(), d.algoType(),
                d.algoVendor(), d.clientName(), d.accountId(), d.symbol(), d.tradeDate(), d.side(),
                d.totalQty(), d.taskAmount(), filledQty, filledAmount == null ? BigDecimal.ZERO : filledAmount,
                avgFillPrice, d.arrivalPrice(),
                marketTime(d.tradeDate(), d.startMinute()), marketTime(d.tradeDate(), d.endMinute()), status);
    }

    private ThirdPartyNormalizer.ThirdPartyOrder toThirdParty(ParentOrderDraft d) {
        return new ThirdPartyNormalizer.ThirdPartyOrder(
                "THIRDA", "TP-" + d.orderId(), d.algoType(), d.accountId(), d.clientName(), d.symbol(),
                "SELL".equals(d.side()) ? 'S' : 'B', d.totalQty(), d.taskAmount(), d.arrivalPrice(),
                marketTime(d.tradeDate(), d.startMinute()).toString().replace('T', ' '),
                marketTime(d.tradeDate(), d.endMinute()).toString().replace('T', ' '));
    }

    private ParentOrderDraft randomDraft(long orderId, LocalDate tradeDate, List<String> symbols,
                                         int marketMinutes, Random random) {
        String algo;
        String algoType;
        double r = random.nextDouble();
        if (r < 0.4) {
            algo = ALGO_TWAP[0];
            algoType = "TWAP";
        } else if (r < 0.8) {
            algo = ALGO_VWAP[0];
            algoType = "VWAP";
        } else {
            algo = ALGO_T0[0];
            algoType = "T0";
        }
        boolean thirdParty = thirdPartyEnabled && random.nextDouble() < 0.10;
        int clientIdx = 1 + random.nextInt(CLIENT_COUNT);
        String client = String.format("机构客户%02d", clientIdx);
        String account = "2023031410" + String.format("%02d", clientIdx);
        String symbol = symbols.get(random.nextInt(symbols.size()));
        BigDecimal taskAmount = BigDecimal.valueOf(500_000 + random.nextDouble() * 19_500_000)
                .setScale(2, RoundingMode.HALF_UP); // 50万~2000万
        int arrivalMinute = random.nextInt(marketMinutes - TwapSlicer.WINDOW_MINUTES - 5);
        int endMinute = Math.min(marketMinutes - 1, arrivalMinute + TwapSlicer.WINDOW_MINUTES);
        return new ParentOrderDraft(orderId, algo, algoType, VENDOR, client, account, symbol, tradeDate,
                random.nextDouble() < 0.7 ? "BUY" : "SELL", taskAmount,
                BigDecimal.ZERO /* arrival 价在 start() 回填 */, arrivalMinute, 0,
                arrivalMinute, endMinute, thirdParty);
    }

    private ParentOrderDraft withQty(ParentOrderDraft draft, BigDecimal arrivalPrice,
                                     List<TickEvent> curve, Random random) {
        long totalQty = Math.max(1000,
                draft.taskAmount().divide(arrivalPrice, 0, RoundingMode.DOWN).longValue() / 100 * 100);
        if (draft.algoType().equals("T0")) {
            // T0 任务口径 = 全部回转腿的名义金额（双腿开平），完成率按此归一
            totalQty = Math.max(2000, totalQty * 2);
            return new ParentOrderDraft(draft.orderId(), draft.algoInstance(), draft.algoType(),
                    draft.algoVendor(), draft.clientName(), draft.accountId(), draft.symbol(),
                    draft.tradeDate(), draft.side(),
                    BigDecimal.valueOf(totalQty).multiply(arrivalPrice).setScale(2, RoundingMode.HALF_UP),
                    arrivalPrice, draft.arrivalMinute(), totalQty, draft.startMinute(), draft.endMinute(),
                    draft.thirdParty());
        }
        return new ParentOrderDraft(draft.orderId(), draft.algoInstance(), draft.algoType(),
                draft.algoVendor(), draft.clientName(), draft.accountId(), draft.symbol(),
                draft.tradeDate(), draft.side(), draft.taskAmount(), arrivalPrice, draft.arrivalMinute(),
                totalQty, draft.startMinute(), draft.endMinute(), draft.thirdParty());
    }

    private SlicingStrategy slicerOf(String algoInstance) {
        if (algoInstance.contains("vwap")) {
            return new VwapSlicer();
        }
        if (algoInstance.contains("t0")) {
            return new T0Slicer();
        }
        return new TwapSlicer();
    }

    private double cancelRatioOf(String algoInstance) {
        if (algoInstance.contains("twap")) {
            return 0.15;
        }
        if (algoInstance.contains("vwap")) {
            return 0.08;
        }
        return 0.10;
    }

    /** 行情时间 → 市场分钟序号（09:30 起 120 分钟，13:00 起续 120 分钟） */
    private int minuteOf(LocalDateTime execTime, LocalDateTime dayFirstTick) {
        LocalTime t = execTime.toLocalTime();
        int morning = (int) java.time.Duration.between(LocalTime.of(9, 30), t).toMinutes();
        if (morning < 120) {
            return morning;
        }
        return 120 + (int) java.time.Duration.between(LocalTime.of(13, 0), t).toMinutes();
    }

    /** 市场分钟序号 → 行情时间 */
    private static LocalDateTime marketTime(LocalDate tradeDate, int minute) {
        if (minute < 120) {
            return LocalDateTime.of(tradeDate, LocalTime.of(9, 30)).plusMinutes(minute);
        }
        return LocalDateTime.of(tradeDate, LocalTime.of(13, 0)).plusMinutes(minute - 120);
    }

    /** 母单号引用：第三方通道归一化后由接入层重分配，子单/终态据此对齐 */
    private static final class OrderRef {
        volatile long orderId;

        OrderRef(long initial) {
            this.orderId = initial;
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
