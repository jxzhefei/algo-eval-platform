package com.algoeval.source.sdk;

import java.time.LocalDate;
import java.util.List;

/**
 * 交易日行情曲线查询：算法执行引擎（collector-trade）据此在任意市场分钟
 * 计算子单成交，保证成交价格与对外推送的行情流完全一致。
 * 由行情源实现（内置生成器同时具备"未来曲线"——模拟交易所的特权）。
 */
public interface QuoteCurveProvider {

    /**
     * @param symbol 标的代码
     * @return 该标的当日全部分钟 tick（按时间升序），未生成时返回空表
     */
    List<TickEvent> dayCurve(LocalDate tradeDate, String symbol);

    /** @return 当日全部标的代码 */
    List<String> symbols(LocalDate tradeDate);

    /** 当日市场总分钟数（如 240） */
    int marketMinutes();
}
