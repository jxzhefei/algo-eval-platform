package com.algoeval.offline.node;

import com.algoeval.offline.dao.TaskDao;
import com.algoeval.offline.domain.ComputeTaskRow;
import com.algoeval.offline.schedule.TaskInitializer;
import com.algoeval.offline.write.ScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 计算节点（全对等，无主备无 leader）：
 * <pre>
 * LOOP:
 *   先查 PENDING 分片（只看，不锁）
 *   乐观锁 UPDATE 抢占：影响行数=1 → 抢到，启动心跳 → 执行 → 置 SUCCESS
 *   =0 → 被抢先，零锁等待，继续下一个
 *   无 PENDING 且无 RUNNING → 本日完成，退出
 * </pre>
 * 抢制天然是 work-stealing：快的节点抢得多，比静态分配负载更均衡。
 */
public class ComputeNodeRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ComputeNodeRunner.class);

    private final String nodeId;
    private final LocalDate tradeDate;
    private final int shardCount;
    private final long heartbeatIntervalMs;
    private final TaskDao taskDao;
    private final TaskInitializer taskInitializer;
    private final ShardExecutor shardExecutor;
    private final ScoringService scoringService;
    private final AtomicBoolean alive = new AtomicBoolean(true);

    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "heartbeat");
                t.setDaemon(true);
                return t;
            });

    public ComputeNodeRunner(String nodeId, LocalDate tradeDate, int shardCount, long heartbeatIntervalMs,
                             TaskDao taskDao, TaskInitializer taskInitializer,
                             ShardExecutor shardExecutor, ScoringService scoringService) {
        this.nodeId = nodeId;
        this.tradeDate = tradeDate;
        this.shardCount = shardCount;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.taskDao = taskDao;
        this.taskInitializer = taskInitializer;
        this.shardExecutor = shardExecutor;
        this.scoringService = scoringService;
    }

    public String nodeId() {
        return nodeId;
    }

    /** 故障注入：模拟宕机——停止心跳与抢占，持有中的分片留给回收者 */
    public void kill() {
        alive.set(false);
        heartbeatExecutor.shutdownNow();
        log.warn("[offline-calculate] 节点 {} 宕机（故障注入）", nodeId);
    }

    @Override
    public void run() {
        // 所有节点都尝试初始化，主键兜底（幂等）
        taskInitializer.initialize(tradeDate, shardCount);
        log.info("[offline-calculate] 节点 {} 上线，开始抢占分片", nodeId);
        while (alive.get()) {
            Optional<ComputeTaskRow> pending = taskDao.findPending(tradeDate);
            if (pending.isEmpty()) {
                if (taskDao.countByStatus(tradeDate, ComputeTaskRow.RUNNING) == 0) {
                    break; // 本日完成
                }
                sleep(500);
                continue;
            }
            ComputeTaskRow task = pending.get();
            if (!taskDao.claim(tradeDate, task.shardNo(), task.version(), nodeId)) {
                continue; // 被别的节点抢先
            }
            ScheduledFuture<?> heartbeat = startHeartbeat(task.shardNo());
            try {
                shardExecutor.execute(tradeDate, task.shardNo(), shardCount, nodeId);
            } catch (Exception e) {
                if (alive.get()) {
                    log.error("[offline-calculate] 节点 {} 执行分片 {} 失败，置 FAILED 由回收/熔断接管",
                            nodeId, task.shardNo(), e);
                    taskDao.markFailed(tradeDate, task.shardNo(), nodeId);
                }
                // alive=false（宕机注入）：保持 RUNNING，等回收者超时回收
            } finally {
                heartbeat.cancel(true);
            }
            // 全部完成时触发评分（锁内幂等，先到者执行）
            scoringService.scoreIfComplete(tradeDate);
        }
        heartbeatExecutor.shutdownNow();
        if (alive.get()) {
            log.info("[offline-calculate] 节点 {} 本日计算完成", nodeId);
        }
    }

    private ScheduledFuture<?> startHeartbeat(int shardNo) {
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                taskDao.renewHeartbeat(tradeDate, shardNo, nodeId);
            } catch (Exception e) {
                log.warn("[offline-calculate] 节点 {} 分片 {} 心跳续约失败: {}", nodeId, shardNo, e.getMessage());
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            alive.set(false);
        }
    }
}
