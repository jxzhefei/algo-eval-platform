package com.algoeval.offline.lock;

/** 锁工厂：本地锁（默认零依赖）/ Redisson（full profile 真实多进程）双实现 */
public interface DistributedLockFactory {

    DistributedLock get(String key);
}
