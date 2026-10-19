package com.algoeval.offline.node;

import com.algoeval.offline.dao.TaskDao;
import com.algoeval.offline.schedule.TaskInitializer;
import com.algoeval.offline.schedule.TaskReaper;
import com.algoeval.offline.write.ScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 离线计算编排：初始化任务（幂等）→ 启动回收者 → 拉起 N 个全对等节点并发抢占 → 等待完成。
 * 节点数是部署配置项——同一套代码，小客户单节点（退化为单机抢占），大客户多节点水平扩展。
 */
@Service
public class OfflineCalcOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OfflineCalcOrchestrator.class);

    private final TaskDao taskDao;
    private final TaskInitializer taskInitializer;
    private final TaskReaper taskReaper;
    private final ShardExecutor shardExecutor;
    private final ScoringService scoringService;

    @Value("${algo.offline.heartbeat-interval-ms:10000}")
    private long heartbeatIntervalMs;
    @Value("${algo.offline.heartbeat-timeout-ms:60000}")
    private long heartbeatTimeoutMs;
    @Value("${algo.offline.reap-interval-ms:15000}")
    private long reapIntervalMs;
    @Value("${algo.offline.max-retry:3}")
    private int maxRetry;

    public OfflineCalcOrchestrator(TaskDao taskDao, TaskInitializer taskInitializer, TaskReaper taskReaper,
                                   ShardExecutor shardExecutor, ScoringService scoringService) {
        this.taskDao = taskDao;
        this.taskInitializer = taskInitializer;
        this.taskReaper = taskReaper;
        this.shardExecutor = shardExecutor;
        this.scoringService = scoringService;
    }

    /** 计算报告 */
    public record CalcReport(long elapsedMs, int shardCount, int nodeCount,
                             List<TaskDao.OwnerStat> ownerStats) {
    }

    /**
     * 执行一个交易日的离线计算（阻塞至全部完成）。
     *
     * @param nodeCount 节点数（单 JVM 多线程模拟多节点）
     */
    public CalcReport run(LocalDate tradeDate, int shardCount, int nodeCount) {
        long start = System.currentTimeMillis();
        log.info("════════ 离线计算启动：交易日 {}，{} 分片 × {} 节点 ════════", tradeDate, shardCount, nodeCount);

        taskInitializer.initialize(tradeDate, shardCount);
        taskReaper.start(tradeDate, reapIntervalMs, heartbeatTimeoutMs, maxRetry);
        try {
            ExecutorService pool = Executors.newFixedThreadPool(nodeCount,
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        return t;
                    });
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < nodeCount; i++) {
                ComputeNodeRunner node = new ComputeNodeRunner("node-" + (i + 1), tradeDate, shardCount,
                        heartbeatIntervalMs, taskDao, taskInitializer, shardExecutor, scoringService);
                futures.add(pool.submit(node));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            pool.shutdown();
        } catch (Exception e) {
            throw new IllegalStateException("离线计算执行失败", e);
        } finally {
            taskReaper.stop();
        }

        long elapsed = System.currentTimeMillis() - start;
        List<TaskDao.OwnerStat> ownerStats = taskDao.ownerStats(tradeDate);
        int failed = taskDao.countByStatus(tradeDate, com.algoeval.offline.domain.ComputeTaskRow.FAILED);
        log.info("════════ 离线计算完成：耗时 {}ms，失败分片 {}，各节点分片数 {} ════════",
                elapsed, failed, ownerStats);
        return new CalcReport(elapsed, shardCount, nodeCount, ownerStats);
    }
}
