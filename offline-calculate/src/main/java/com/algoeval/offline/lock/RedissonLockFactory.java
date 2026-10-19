package com.algoeval.offline.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redisson 分布式锁实现（真实多进程部署）：
 * 看门狗 30s 自动续期，tryLock(等待5s, 持锁60s) 兜底。
 * RedissonClient 存在时激活（@Primary 覆盖本地实现）；无 Redis 上下文（单测）退化为 JVM 内锁。
 */
@Component
@ConditionalOnBean(RedissonClient.class)
@Primary
public class RedissonLockFactory implements DistributedLockFactory {

    private static final Logger log = LoggerFactory.getLogger(RedissonLockFactory.class);

    private final RedissonClient redissonClient;

    public RedissonLockFactory(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public DistributedLock get(String key) {
        RLock lock = redissonClient.getLock(key);
        return new DistributedLock() {
            @Override
            public boolean tryLock(long waitMs, long leaseMs) throws InterruptedException {
                return lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
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
