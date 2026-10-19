package com.algoeval.collector.hq.leader;

import com.algoeval.infra.leader.LeaderElector;
import jakarta.annotation.PreDestroy;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 选主实现（演进决策 §13.6：etcd 退役，选主收敛 Redis）：
 * 抢占分布式锁 lock:hq-leader 并以看门狗续期（leaseTime=-1 自动续约）；
 * 锁丢失（Redis 主从切换等）最坏后果为短暂双主 → 行情重复分发由下游幂等 upsert 吸收，
 * 与汇总锁同一套容错逻辑。备节点每 5s 重试抢锁，主节点宕机后秒级接管。
 * RedissonClient 存在时激活（@Primary 覆盖单节点实现）；无 Redis 上下文退化为恒主。
 */
@Component
@ConditionalOnBean(RedissonClient.class)
@Primary
public class RedissonLeaderElector implements LeaderElector {

    private static final Logger log = LoggerFactory.getLogger(RedissonLeaderElector.class);
    private static final String LOCK_KEY = "lock:hq-leader";

    private final RedissonClient redisson;
    private final RLock lock;
    private final ScheduledExecutorService guard =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hq-leader-guard");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean leader = false;

    public RedissonLeaderElector(RedissonClient redisson) {
        this.redisson = redisson;
        this.lock = redisson.getLock(LOCK_KEY);
        guard.scheduleWithFixedDelay(this::ensureLeadership, 0, 5, TimeUnit.SECONDS);
    }

    private void ensureLeadership() {
        try {
            if (leader) {
                if (!lock.isHeldByCurrentThread()) {
                    leader = false;
                    log.warn("[collector-hq] 选主锁丢失，本节点降级为备节点（等待重新抢锁）");
                }
                return;
            }
            if (lock.tryLock(0, -1, TimeUnit.MILLISECONDS)) {
                leader = true;
                log.info("[collector-hq] 本节点成为主采集节点（看门狗续期中）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[collector-hq] 选主抢锁失败（稍后重试）: {}", e.getMessage());
        }
    }

    @Override
    public boolean isLeader() {
        return leader;
    }

    @PreDestroy
    public void stop() {
        guard.shutdownNow();
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception ignored) {
        }
    }
}
