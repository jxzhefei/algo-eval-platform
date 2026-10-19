package com.algoeval.offline.lock;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JVM 本地锁实现（默认轨道）：ConcurrentHashMap&lt;String, ReentrantLock&gt;。
 * 单 JVM 多线程模拟多节点时有效；真实多进程部署用 redis profile 切换 Redisson 实现。
 */
@Component
@Profile("!redis")
public class LocalLockFactory implements DistributedLockFactory {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public DistributedLock get(String key) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock(true));
        return new DistributedLock() {
            @Override
            public boolean tryLock(long waitMs, long leaseMs) throws InterruptedException {
                return lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
            }

            @Override
            public void unlock() {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        };
    }
}
