package com.algoeval.stream;

import com.algoeval.ingest.model.ChildOrderMsg;
import com.algoeval.ingest.model.ParentOrderMsg;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑动窗口实时绩效跟踪：日内实时指标是"有限状态增量聚合"——
 * 累计成交额/成交量滚动更新 VWAP 偏离，撤单率用 5 分钟滑动窗口。
 * （私有化场景刻意不上 Flink：Java 内存增量计算足够，客户机房运维不起流式集群）
 */
@Component
public class SlidingWindowTracker {

    private static final long WINDOW_NANOS = 5L * 60 * 1_000_000_000L; // 5 分钟（真实时间）

    private final Map<String, AlgoState> states = new ConcurrentHashMap<>();
    private final Map<Long, ParentOrderMsg> parents = new ConcurrentHashMap<>();

    /** 单算法实例的增量状态 */
    static class AlgoState {
        BigDecimal filledTurnover = BigDecimal.ZERO;  // Σ 成交金额
        long filledQty = 0;                            // Σ 成交量
        BigDecimal arrivalBasis = BigDecimal.ZERO;     // Σ 成交量 × arrival 价（方向调整基准）
        long sellQty = 0;
        /** 窗口内事件：[nanoTime, cancelled?1:0] */
        final Deque<long[]> windowEvents = new ArrayDeque<>();
        int windowTotal = 0;
        int windowCancelled = 0;
        final java.util.Set<Long> activeOrders = new java.util.HashSet<>();
        java.time.LocalDateTime lastMarketTime;
    }

    public void onParent(ParentOrderMsg p) {
        parents.put(p.orderId(), p);
        states.computeIfAbsent(p.algoInstance(), k -> new AlgoState()).activeOrders.add(p.orderId());
    }

    public void onChild(ChildOrderMsg c, long nowNanos) {
        ParentOrderMsg parent = parents.get(c.orderId());
        if (parent == null) {
            return; // 母单消息尚未到达（分区乱序），跳过——下一轮窗口自然修正
        }
        AlgoState state = states.computeIfAbsent(parent.algoInstance(), k -> new AlgoState());
        synchronized (state) {
            if (c.filledQty() > 0 && c.avgFillPrice() != null) {
                BigDecimal fillAmount = c.avgFillPrice().multiply(BigDecimal.valueOf(c.filledQty()));
                state.filledTurnover = state.filledTurnover.add(fillAmount);
                state.filledQty += c.filledQty();
                state.arrivalBasis = state.arrivalBasis
                        .add(parent.arrivalPrice().multiply(BigDecimal.valueOf(c.filledQty())));
                if ("SELL".equals(parent.side())) {
                    state.sellQty += c.filledQty();
                }
            }
            // 滑动窗口撤单统计
            state.windowEvents.addLast(new long[]{nowNanos, c.cancelled() ? 1 : 0});
            state.windowTotal++;
            if (c.cancelled()) {
                state.windowCancelled++;
            }
            evictExpired(state, nowNanos);
            state.lastMarketTime = c.execTime();
        }
    }

    private void evictExpired(AlgoState state, long nowNanos) {
        while (!state.windowEvents.isEmpty() && nowNanos - state.windowEvents.peekFirst()[0] > WINDOW_NANOS) {
            long[] evicted = state.windowEvents.pollFirst();
            state.windowTotal--;
            state.windowCancelled -= (int) evicted[1];
        }
    }

    /**
     * 当前快照。
     *
     * @return runningVwapBps（进行中母单已成交部分相对 arrival 基准的方向调整偏离）、窗口撤单率、进行中母单数、最新行情时间
     */
    public Snapshot snapshot(String algoInstance, long nowNanos) {
        AlgoState state = states.get(algoInstance);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            evictExpired(state, nowNanos);
            BigDecimal runningBps = null;
            if (state.filledQty > 0 && state.arrivalBasis.signum() > 0) {
                BigDecimal avgFill = state.filledTurnover.divide(BigDecimal.valueOf(state.filledQty),
                        6, RoundingMode.HALF_UP);
                BigDecimal arrivalAvg = state.arrivalBasis.divide(BigDecimal.valueOf(state.filledQty),
                        6, RoundingMode.HALF_UP);
                // 方向调整：BUY 贵了为正（差）；SELL 卖低了为正（差）
                BigDecimal diff = avgFill.subtract(arrivalAvg);
                if (state.sellQty * 2 > state.filledQty) { // SELL 为主则取反
                    diff = diff.negate();
                }
                runningBps = diff.multiply(BigDecimal.valueOf(10_000))
                        .divide(arrivalAvg, 4, RoundingMode.HALF_UP);
            }
            BigDecimal cancelRatio = state.windowTotal == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(state.windowCancelled)
                    .divide(BigDecimal.valueOf(state.windowTotal), 4, RoundingMode.HALF_UP);
            return new Snapshot(runningBps, cancelRatio, state.activeOrders.size(), state.lastMarketTime);
        }
    }

    public java.util.Set<String> algoInstances() {
        return states.keySet();
    }

    /** 收盘后清理（演示日终态回收） */
    public void clear() {
        states.clear();
        parents.clear();
    }

    public record Snapshot(BigDecimal runningVwapBps, BigDecimal cancelRatio, int activeOrders,
                           java.time.LocalDateTime lastMarketTime) {
    }
}
