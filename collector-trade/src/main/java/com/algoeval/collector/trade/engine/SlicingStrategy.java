package com.algoeval.collector.trade.engine;

import com.algoeval.source.sdk.TickEvent;

import java.util.List;
import java.util.Random;

/**
 * 拆单策略：把母单总量切成若干子单计划。
 * 不同算法的行为差异（时间切片方式）是绩效差异的真实来源。
 */
public interface SlicingStrategy {

    /**
     * @param order       母单草稿
     * @param dayCurve    标的当日行情曲线（240 分钟）
     * @param childCount  子单笔数（5~30）
     * @param random      确定性随机源
     * @return 子单计划列表（qty 之和 = 母单总量）
     */
    List<ChildSpec> slice(ParentOrderDraft order, List<TickEvent> dayCurve, int childCount, Random random);
}
