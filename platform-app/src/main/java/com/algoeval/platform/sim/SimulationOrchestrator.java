package com.algoeval.platform.sim;

import com.algoeval.collector.hq.HqCollectorService;
import com.algoeval.collector.trade.engine.AlgoExecutionEngine;
import com.algoeval.infra.cache.TwoLevelCache;
import com.algoeval.offline.node.OfflineCalcOrchestrator;
import com.algoeval.source.mock.MockExchangeFeed;
import com.algoeval.storer.CkSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 模拟交易日编排（行情走 JeroMQ 传输契约，交易走 REST 接入层——与生产链路同构）：
 * 证券主档就绪 → 行情前置 PUB 绑定 → collector-hq SUB 订阅（Disruptor→Kafka）
 * → 模拟交易终端经 trade-ingest 上报 → 收盘触发离线计算 → CK 同步与对账。
 */
@Component
public class SimulationOrchestrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationOrchestrator.class);

    private final HqCollectorService hqCollectorService;
    private final com.algoeval.collector.trade.engine.AlgoExecutionEngine tradeEngine;
    private final OfflineCalcOrchestrator offlineCalcOrchestrator;
    private final ConfigurableApplicationContext context;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<CkSyncService> ckSyncProvider;
    private final TwoLevelCache cache;
    private final com.algoeval.source.sdk.SqlDialect sqlDialect;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "simulation-orchestrator");
                t.setDaemon(true);
                return t;
            });

    @Value("${algo.sim.symbols:100}")
    private int symbols;
    @Value("${algo.sim.seed:42}")
    private long seed;
    @Value("${algo.sim.millis-per-minute:1000}")
    private long millisPerMinute;
    @Value("${algo.sim.shards:32}")
    private int shards;
    @Value("${algo.sim.nodes:3}")
    private int nodes;
    @Value("${algo.hq.endpoint:tcp://127.0.0.1:5570}")
    private String hqEndpoint;

    private volatile LocalDate tradeDate;
    private volatile OfflineCalcOrchestrator.CalcReport lastCalcReport;
    private MockExchangeFeed feed;

    public SimulationOrchestrator(HqCollectorService hqCollectorService,
                                  com.algoeval.collector.trade.engine.AlgoExecutionEngine tradeEngine,
                                  OfflineCalcOrchestrator offlineCalcOrchestrator,
                                  ConfigurableApplicationContext context,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectProvider<CkSyncService> ckSyncProvider,
                                  TwoLevelCache cache,
                                  com.algoeval.source.sdk.SqlDialect sqlDialect) {
        this.hqCollectorService = hqCollectorService;
        this.tradeEngine = tradeEngine;
        this.offlineCalcOrchestrator = offlineCalcOrchestrator;
        this.context = context;
        this.jdbcTemplate = jdbcTemplate;
        this.ckSyncProvider = ckSyncProvider;
        this.cache = cache;
        this.sqlDialect = sqlDialect;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        tradeDate = LocalDate.now();
        log.info("════════════════ 模拟交易日 {} 开始（JeroMQ 行情前置 {}）════════════════", tradeDate, hqEndpoint);

        // 1. 行情前置（PUB）先行绑定；模拟终端的"未来曲线"由 feed 提供
        feed = new MockExchangeFeed();
        Map<String, String> feedProps = new HashMap<>();
        feedProps.put("symbols", String.valueOf(symbols));
        feedProps.put("seed", String.valueOf(seed));
        feedProps.put("millisPerMinute", String.valueOf(millisPerMinute));
        feedProps.put("endpoint", hqEndpoint);
        feed.configure(feedProps);
        feed.start(tradeDate);
        context.getBeanFactory().registerSingleton("quoteCurveProvider", feed);
        seedSecuSymbols(feed.symbols(tradeDate));

        // 2. PUB/SUB 慢加入者规避：订阅端在发布节奏开始前完成连接
        Thread.sleep(800);

        // 3. 采集（SUB→Disruptor→Kafka）与交易终端（REST→ingest→Kafka）启动
        Map<String, String> sourceProps = new HashMap<>();
        sourceProps.put("endpoint", hqEndpoint);
        hqCollectorService.start(tradeDate, sourceProps);
        tradeEngine.start(tradeDate);

        // 4. 收盘后触发离线计算：240 市场分钟播完 + 15s 消费排空缓冲
        long closeDelayMs = 240L * millisPerMinute + 15_000;
        scheduler.schedule((Runnable) this::runOfflineCalculation, closeDelayMs, TimeUnit.MILLISECONDS);
        log.info("[platform] 交易日时间线已排定：{}ms 后收盘，随后触发离线计算（{} 节点 × {} 分片）",
                closeDelayMs, nodes, shards);
    }

    /** 盘后离线计算（幂等：重复触发只会覆盖同 key 数据），完成后同步 CK 并失效缓存 */
    public void runOfflineCalculation() {
        runOfflineCalculation(tradeDate);
    }

    /** 指定交易日的盘后离线计算（运维补算入口，幂等） */
    public void runOfflineCalculation(LocalDate date) {
        log.info("[platform] 触发盘后离线计算: {}", date);
        lastCalcReport = offlineCalcOrchestrator.run(date, shards, nodes);
        syncClickHouse(date);
        cache.evict("stat:");
        cache.evict("portrait:");
    }

    /** MySQL→ClickHouse 日同步与对账（不可达时告警跳过，MySQL 为主源） */
    public Map<String, Object> syncClickHouse(LocalDate date) {
        CkSyncService ckSync = ckSyncProvider.getIfAvailable();
        if (ckSync == null) {
            return Map.of("skipped", "ck-disabled");
        }
        try {
            return ckSync.syncDay(date);
        } catch (Exception e) {
            log.warn("[platform] CK 同步失败（MySQL 为主源，不影响评价结果）: {}", e.getMessage());
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    public OfflineCalcOrchestrator.CalcReport lastCalcReport() {
        return lastCalcReport;
    }

    public LocalDate tradeDate() {
        return tradeDate;
    }

    /** 证券主档落库（市占率分母与标的特征分析的数据基础，真实表 secu_symbol） */
    private void seedSecuSymbols(List<String> symbols) {
        String[] columns = {"symbol", "symbol_name", "exchange", "list_date", "float_shares",
                "is_active", "update_time"};
        String upsert = sqlDialect.upsert("secu_symbol", columns, 1);
        jdbcTemplate.batchUpdate(upsert, symbols, 500, (ps, symbol) -> {
            String exchange = symbol.endsWith("SH") ? "SSE" : "SZSE";
            long floatShares = 50_000_000L + ((long) Math.abs(symbol.hashCode()) % 1000) * 1_000_000L;
            ps.setString(1, symbol);
            ps.setString(2, "模拟标的" + symbol.substring(0, 6));
            ps.setString(3, exchange);
            ps.setDate(4, java.sql.Date.valueOf("2020-01-01"));
            ps.setLong(5, floatShares);
            ps.setInt(6, 1);
            ps.setTimestamp(7, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
        });
        log.info("[platform] 证券主档就绪：{} 只标的", symbols.size());
    }
}
