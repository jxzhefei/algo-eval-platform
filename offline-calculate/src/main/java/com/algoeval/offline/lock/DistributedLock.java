package com.algoeval.offline.lock;

/**
 * 分布式锁抽象：保护分层汇总的"读-改-写"。
 * 锁粒度 lock:summary:{algoInstance}:{tradeDate}——冲突域极小（同实例同日的分片数）。
 */
public interface DistributedLock {

    /**
     * @param waitMs  等待锁的最长时间
     * @param leaseMs 持锁时间（Redisson 看门狗自动续期场景下为兜底）
     * @return 是否抢到锁
     */
    boolean tryLock(long waitMs, long leaseMs) throws InterruptedException;

    void unlock();
}
