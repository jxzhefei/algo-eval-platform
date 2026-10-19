package com.algoeval.offline.tx;

import org.apache.seata.spring.annotation.GlobalTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Seata AT 全局事务钩子（旗舰档默认启用，§13.5）：
 * 分片批次提交——同一全局事务内写 indicator_result（结果域）+ compute_task 状态（调度域），
 * 跨 schema 原子提交，undo_log 自动回滚。
 * 事务边界收窄到"批次提交"粒度，计算过程不开全局事务（AT 全局锁有开销）。
 * seata.enabled=false 或 TC 不可用的上下文（单测）退化为 Noop 本地事务。
 */
@Component
@ConditionalOnProperty(name = "seata.enabled", havingValue = "true")
@Primary
public class SeataTransactionHook implements TransactionHook {

    private static final Logger log = LoggerFactory.getLogger(SeataTransactionHook.class);

    @Override
    @GlobalTransactional(name = "offline-shard-commit", rollbackFor = Exception.class)
    public <T> T inBatchTransaction(String batchName, Supplier<T> work) {
        log.debug("[offline-calculate] Seata 全局事务提交批次: {}", batchName);
        return work.get();
    }
}
