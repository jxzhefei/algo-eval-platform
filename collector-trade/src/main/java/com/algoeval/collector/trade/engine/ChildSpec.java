package com.algoeval.collector.trade.engine;

/**
 * 子单计划：执行的市场分钟 + 委托数量 + 买卖方向（T0 回转双腿）。
 */
public record ChildSpec(int execMinute, long qty, String side) {
}
