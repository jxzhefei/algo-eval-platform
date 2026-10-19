package com.algoeval.offline.schedule;

import com.algoeval.offline.dao.TaskDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 超时回收（每个节点都是回收者）：
 * 心跳超时的 RUNNING 任务用乐观锁重置为 PENDING——回收动作也是抢占式，
 * 不存在"谁监控监控者"的问题。重试超限熔断 FAILED，避免毒丸任务无限循环。
 */
@Component
public class TaskReaper {

    private static final Logger log = LoggerFactory.getLogger(TaskReaper.class);

    private final TaskDao taskDao;
    private volatile ScheduledExecutorService executor;

    public TaskReaper(TaskDao taskDao) {
        this.taskDao = taskDao;
    }

    public synchronized void start(LocalDate tradeDate, long reapIntervalMs, long heartbeatTimeoutMs, int maxRetry) {
        stop();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-reaper");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                int reclaimed = taskDao.reclaimStale(tradeDate, heartbeatTimeoutMs);
                if (reclaimed > 0) {
                    log.warn("[offline-calculate] 回收 {} 个心跳超时分片（{}ms 无心跳），重置为 PENDING 等待重算",
                            reclaimed, heartbeatTimeoutMs);
                }
                int broken = taskDao.circuitBreak(tradeDate, maxRetry);
                if (broken > 0) {
                    log.error("[offline-calculate] 熔断：{} 个分片重试超过 {} 次，置 FAILED 待人工介入", broken, maxRetry);
                }
            } catch (Exception e) {
                log.error("[offline-calculate] 回收扫描失败", e);
            }
        }, reapIntervalMs, reapIntervalMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
