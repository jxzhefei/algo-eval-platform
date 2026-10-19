package com.algoeval.offline.verify;

import com.algoeval.offline.dao.ResultDao;
import com.algoeval.offline.dao.SummaryDao;
import com.algoeval.offline.node.OfflineCalcOrchestrator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 正确性对账：3 万母单，单机结果集 A vs 3 节点并发结果集 B，逐行比对。
 * 断言：不重（行数==母单数）、不漏（差集为空）、不脏（每行指标值相等）。
 */
@SpringBootTest(classes = OfflineCalcTestApp.class)
class CorrectnessVerifierTest {

    private static final Logger log = LoggerFactory.getLogger(CorrectnessVerifierTest.class);
    private static final int PARENT_COUNT = 30_000;
    private static final int SHARDS = 32;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OfflineCalcOrchestrator orchestrator;
    @Autowired
    private ResultDao resultDao;
    @Autowired
    private SummaryDao summaryDao;

    @Test
    void multiNodeResultEqualsSingleNode() {
        TestDataGenerator generator = new TestDataGenerator(jdbcTemplate, 42L);
        log.info("[verify] 生成验证数据集：{} 母单 ...", PARENT_COUNT);
        generator.generate(PARENT_COUNT);

        log.info("[verify] === 第 1 遍：单机模式（1 节点） ===");
        OfflineCalcOrchestrator.CalcReport single = orchestrator.run(TestDataGenerator.TRADE_DATE, SHARDS, 1);
        ResultSnapshot baseline = ResultSnapshot.take(resultDao, summaryDao, TestDataGenerator.TRADE_DATE);
        assertEquals(PARENT_COUNT, baseline.results().size(), "结果行数必须等于母单数（不重不漏）");

        generator.resetComputeArtifacts();

        log.info("[verify] === 第 2 遍：3 节点并发抢占 ===");
        OfflineCalcOrchestrator.CalcReport multi = orchestrator.run(TestDataGenerator.TRADE_DATE, SHARDS, 3);
        ResultSnapshot concurrent = ResultSnapshot.take(resultDao, summaryDao, TestDataGenerator.TRADE_DATE);

        String diff = baseline.diff(concurrent);
        assertNull(diff, "多节点结果集与单机基准不一致: " + diff);
        log.info("[verify] ✅ 正确性对账通过：单机 {}ms vs 3 节点 {}ms，{} 行结果逐行相等",
                single.elapsedMs(), multi.elapsedMs(), baseline.results().size());
        log.info("[verify] work-stealing 分布: {}", multi.ownerStats());
    }
}
