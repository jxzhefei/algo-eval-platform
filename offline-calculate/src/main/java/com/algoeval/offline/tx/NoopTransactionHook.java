package com.algoeval.offline.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 默认事务钩子：本地事务兜底（标准档私有化部署单库本地事务即足够，不开 Seata）。
 */
@Component
@Profile("!seata")
public class NoopTransactionHook implements TransactionHook {

    private static final Logger log = LoggerFactory.getLogger(NoopTransactionHook.class);
    private static volatile boolean logged = false;

    private final TransactionTemplate transactionTemplate;

    public NoopTransactionHook(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public <T> T inBatchTransaction(String batchName, Supplier<T> work) {
        if (!logged) {
            logged = true;
            log.info("[offline-calculate] Seata 未启用，本地事务兜底（私有化标准档默认形态）");
        }
        return transactionTemplate.execute(status -> work.get());
    }
}
