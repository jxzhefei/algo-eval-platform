package com.algoeval.offline.tx;

import java.util.function.Supplier;

/**
 * 事务钩子：分片批次提交的跨库一致性抽象。
 * <ul>
 *   <li>默认轨道：{@link NoopTransactionHook}——本地事务兜底（单库，日志打印"Seata 未启用"）</li>
 *   <li>full profile（seata）：SeataTransactionHook 以 @GlobalTransactional 跨双 schema 原子提交</li>
 * </ul>
 */
public interface TransactionHook {

    /** 在（全局/本地）事务边界内执行批次提交 */
    <T> T inBatchTransaction(String batchName, Supplier<T> work);
}
