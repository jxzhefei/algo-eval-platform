package com.algoeval.offline.verify;

import com.algoeval.offline.node.OfflineCalcOrchestrator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 性能对比：1 / 2 / 3 节点（线程模拟）分别执行，输出耗时与加速比表格，
 * 并展示 work-stealing 效果（快的节点抢得多）。
 */
@SpringBootTest(classes = OfflineCalcTestApp.class)
class BenchmarkRunnerTest {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunnerTest.class);
    private static final int PARENT_COUNT = 12_000;
    private static final int SHARDS = 32;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OfflineCalcOrchestrator orchestrator;

    @Test
    void benchmarkSpeedup() {
        TestDataGenerator generator = new TestDataGenerator(jdbcTemplate, 777L);
        generator.generate(PARENT_COUNT);

        StringBuilder table = new StringBuilder("\n  节点数 | 总耗时(ms) | 加速比 | 每节点完成分片数\n");
        table.append("  -------+-----------+-------+------------------\n");
        long baselineMs = -1;
        long threeNodeMs = -1;
        for (int nodes : new int[]{1, 2, 3}) {
            generator.resetComputeArtifacts();
            OfflineCalcOrchestrator.CalcReport report = orchestrator.run(TestDataGenerator.TRADE_DATE, SHARDS, nodes);
            if (nodes == 1) {
                baselineMs = report.elapsedMs();
            }
            if (nodes == 3) {
                threeNodeMs = report.elapsedMs();
            }
            table.append(String.format("    %d    |  %8d  |  %4.2fx | %s%n",
                    nodes, report.elapsedMs(), (double) baselineMs / report.elapsedMs(),
                    report.ownerStats()));
        }
        log.info("[verify] 性能对比（{} 母单 × {} 分片）:{}", PARENT_COUNT, SHARDS, table);
        assertTrue(threeNodeMs < baselineMs,
                "3 节点应快于单节点（加速比 " + String.format("%.2f", (double) baselineMs / threeNodeMs) + "x）");
    }
}
