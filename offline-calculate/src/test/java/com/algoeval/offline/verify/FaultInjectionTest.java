package com.algoeval.offline.verify;

import com.algoeval.offline.dao.ResultDao;
import com.algoeval.offline.dao.SummaryDao;
import com.algoeval.offline.dao.TaskDao;
import com.algoeval.offline.node.ComputeNodeRunner;
import com.algoeval.offline.node.ShardExecutor;
import com.algoeval.offline.schedule.TaskInitializer;
import com.algoeval.offline.schedule.TaskReaper;
import com.algoeval.offline.write.ScoringService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 故障注入：3 节点开跑 → 运行中强杀"节点B"（停止线程，心跳停止）→
 * 预期 60s（测试配置 2s）内其 RUNNING 分片被回收为 PENDING → 被其他节点重算 →
 * 最终结果集仍与单机基准完全一致。
 */
@SpringBootTest(classes = OfflineCalcTestApp.class)
class FaultInjectionTest {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectionTest.class);
    private static final int PARENT_COUNT = 10_000;
    private static final int SHARDS = 32;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TaskDao taskDao;
    @Autowired
    private TaskInitializer taskInitializer;
    @Autowired
    private TaskReaper taskReaper;
    @Autowired
    private ShardExecutor shardExecutor;
    @Autowired
    private ScoringService scoringService;
    @Autowired
    private ResultDao resultDao;
    @Autowired
    private SummaryDao summaryDao;
    @Autowired
    private com.algoeval.offline.node.OfflineCalcOrchestrator orchestrator;

    @Value("${algo.offline.heartbeat-interval-ms:200}")
    private long heartbeatIntervalMs;
    @Value("${algo.offline.heartbeat-timeout-ms:2000}")
    private long heartbeatTimeoutMs;
    @Value("${algo.offline.reap-interval-ms:500}")
    private long reapIntervalMs;
    @Value("${algo.offline.max-retry:3}")
    private int maxRetry;

    @Test
    void killedNodeShardsAreReclaimedAndRecomputed() throws Exception {
        TestDataGenerator generator = new TestDataGenerator(jdbcTemplate, 999L);
        generator.generate(PARENT_COUNT);

        log.info("[verify] === 基准：单机模式 ===");
        orchestrator.run(TestDataGenerator.TRADE_DATE, SHARDS, 1);
        ResultSnapshot baseline = ResultSnapshot.take(resultDao, summaryDao, TestDataGenerator.TRADE_DATE);
        generator.resetComputeArtifacts();

        log.info("[verify] === 故障注入：3 节点开跑，1.5s 后强杀 node-2 ===");
        taskInitializer.initialize(TestDataGenerator.TRADE_DATE, SHARDS);
        taskReaper.start(TestDataGenerator.TRADE_DATE, reapIntervalMs, heartbeatTimeoutMs, maxRetry);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<ComputeNodeRunner> nodes = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ComputeNodeRunner node = new ComputeNodeRunner("node-" + i, TestDataGenerator.TRADE_DATE, SHARDS,
                    heartbeatIntervalMs, taskDao, taskInitializer, shardExecutor, scoringService);
            nodes.add(node);
            futures.add(pool.submit(node));
        }

        Thread.sleep(1500);
        nodes.get(1).kill(); // 强杀 node-2：持有中的 RUNNING 分片心跳停止

        for (Future<?> future : futures) {
            future.get();
        }
        taskReaper.stop();
        pool.shutdown();

        // 回收链路验证：node-2 被回收的分片已被重算（retry_count>0 或全部 SUCCESS）
        int running = taskDao.countByStatus(TestDataGenerator.TRADE_DATE,
                com.algoeval.offline.domain.ComputeTaskRow.RUNNING);
        int pending = taskDao.countByStatus(TestDataGenerator.TRADE_DATE,
                com.algoeval.offline.domain.ComputeTaskRow.PENDING);
        assertEquals(0, running + pending, "宕机节点的分片必须被回收重算完毕");
        log.info("[verify] 回收链路：检测到超时 → 回收 → 其他节点抢占重算完成，各节点分片数 {}",
                taskDao.ownerStats(TestDataGenerator.TRADE_DATE));

        ResultSnapshot afterFault = ResultSnapshot.take(resultDao, summaryDao, TestDataGenerator.TRADE_DATE);
        assertEquals(PARENT_COUNT, afterFault.results().size(), "结果行数必须等于母单数");
        String diff = baseline.diff(afterFault);
        assertNull(diff, "故障重算后结果集与单机基准不一致: " + diff);
        log.info("[verify] ✅ 故障注入测试通过：节点宕机不影响最终结果正确性");
    }
}
