package com.algoeval.collector.trade.engine;

import com.algoeval.source.sdk.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * TWAP：全天执行窗口内均匀时间切片——不看成交量分布，
 * 午间低流动性时段照样下同样大的单（冲击成本高的根源）。
 */
public class TwapSlicer implements SlicingStrategy {

    static final int WINDOW_MINUTES = 60;

    @Override
    public List<ChildSpec> slice(ParentOrderDraft order, List<TickEvent> dayCurve, int childCount, Random random) {
        int start = order.arrivalMinute();
        int end = Math.min(dayCurve.size() - 1, start + WINDOW_MINUTES);
        List<ChildSpec> specs = new ArrayList<>(childCount);
        long remaining = order.totalQty();
        for (int i = 0; i < childCount; i++) {
            int minute = start + (int) Math.round((double) i * (end - start) / Math.max(1, childCount - 1));
            long qty = (i == childCount - 1) ? remaining : roundLot(order.totalQty() / childCount, random);
            remaining -= qty;
            specs.add(new ChildSpec(minute, qty, order.side()));
        }
        return specs;
    }

    static long roundLot(long qty, Random random) {
        long jittered = Math.max(100, qty + (long) (qty * 0.1 * (random.nextDouble() - 0.5)));
        return Math.max(100, jittered / 100 * 100);
    }
}
