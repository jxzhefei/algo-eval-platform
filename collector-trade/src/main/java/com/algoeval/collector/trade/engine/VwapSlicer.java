package com.algoeval.collector.trade.engine;

import com.algoeval.source.sdk.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * VWAP：按执行窗口内的历史/预测成交量分布加权切片——
 * 单子大小与流动性成正比，天然贴近市场 VWAP（绩效更好的根源）。
 */
public class VwapSlicer implements SlicingStrategy {

    @Override
    public List<ChildSpec> slice(ParentOrderDraft order, List<TickEvent> dayCurve, int childCount, Random random) {
        int start = order.arrivalMinute();
        int end = Math.min(dayCurve.size() - 1, start + TwapSlicer.WINDOW_MINUTES);
        // 与 TWAP 相同的执行时点，但权重跟随成交量分布
        int[] minutes = new int[childCount];
        double[] weights = new double[childCount];
        double totalWeight = 0;
        for (int i = 0; i < childCount; i++) {
            minutes[i] = start + (int) Math.round((double) i * (end - start) / Math.max(1, childCount - 1));
            weights[i] = Math.max(1, dayCurve.get(minutes[i]).volume());
            totalWeight += weights[i];
        }
        List<ChildSpec> specs = new ArrayList<>(childCount);
        long remaining = order.totalQty();
        for (int i = 0; i < childCount; i++) {
            long qty = (i == childCount - 1) ? remaining
                    : Math.max(100, Math.round(order.totalQty() * weights[i] / totalWeight / 100.0) * 100);
            remaining -= qty;
            specs.add(new ChildSpec(minutes[i], qty, order.side()));
        }
        return specs;
    }
}
