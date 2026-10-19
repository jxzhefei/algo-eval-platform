package com.algoeval.collector.trade.engine;

import com.algoeval.source.sdk.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * T0：日内回转高抛低吸——每个子窗口内于局部低点 BUY、局部高点 SELL 成对开平，
 * 腿与腿构成回转（round trip），捕捉日内小幅波动（10~20bps 量级）。
 * 双腿行为使 T0 明细（买入额/卖出额/回转次数/净收益）可被评价体系还原。
 */
public class T0Slicer implements SlicingStrategy {

    @Override
    public List<ChildSpec> slice(ParentOrderDraft order, List<TickEvent> dayCurve, int childCount, Random random) {
        int start = order.arrivalMinute();
        int end = Math.min(dayCurve.size() - 1, start + TwapSlicer.WINDOW_MINUTES);
        int legs = Math.max(2, childCount / 2);          // 每回转 2 腿
        int subWindow = Math.max(3, (end - start) / legs);
        long legQty = Math.max(100, order.totalQty() / legs / 100 * 100);

        List<ChildSpec> specs = new ArrayList<>(legs * 2);
        for (int w = start + 1; w <= end && specs.size() < childCount; w += subWindow) {
            int wEnd = Math.min(end, w + subWindow - 1);
            int low = w;
            int high = w;
            for (int m = w + 1; m <= wEnd; m++) {
                if (dayCurve.get(m).price().compareTo(dayCurve.get(low).price()) < 0) {
                    low = m;
                }
                if (dayCurve.get(m).price().compareTo(dayCurve.get(high).price()) > 0) {
                    high = m;
                }
            }
            if (low == high) {
                continue;
            }
            // 先出现的腿先发（时间顺序自然成立）
            if (low < high) {
                specs.add(new ChildSpec(low, legQty, "BUY"));
                specs.add(new ChildSpec(high, legQty, "SELL"));
            } else {
                specs.add(new ChildSpec(high, legQty, "SELL"));
                specs.add(new ChildSpec(low, legQty, "BUY"));
            }
        }
        return specs;
    }
}
