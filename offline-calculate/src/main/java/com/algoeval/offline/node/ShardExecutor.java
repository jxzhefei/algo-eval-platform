package com.algoeval.offline.node;

import com.algoeval.offline.dao.MarketDao;
import com.algoeval.offline.dao.OrderDao;
import com.algoeval.offline.dao.ResultDao;
import com.algoeval.offline.dao.TaskDao;
import com.algoeval.offline.domain.ChildOrderRow;
import com.algoeval.offline.domain.IndicatorResultRow;
import com.algoeval.offline.domain.ParentOrderRow;
import com.algoeval.offline.indicator.IndicatorFactory;
import com.algoeval.offline.indicator.IndicatorStrategy;
import com.algoeval.offline.tx.TransactionHook;
import com.algoeval.offline.write.SummaryAggregator;
import com.algoeval.offline.write.T0DetailWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分片执行器：加载分片母单 → 批量加载子单 → 策略工厂逐单计算（18 指标）
 * → T0 母单回转明细 → 幂等落库 → 锁汇总。
 */
@Component
public class ShardExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShardExecutor.class);

    private final OrderDao orderDao;
    private final MarketDao marketDao;
    private final ResultDao resultDao;
    private final TaskDao taskDao;
    private final IndicatorFactory indicatorFactory;
    private final SummaryAggregator summaryAggregator;
    private final T0DetailWriter t0DetailWriter;
    private final TransactionHook transactionHook;

    public ShardExecutor(OrderDao orderDao, MarketDao marketDao, ResultDao resultDao, TaskDao taskDao,
                         IndicatorFactory indicatorFactory, SummaryAggregator summaryAggregator,
                         T0DetailWriter t0DetailWriter, TransactionHook transactionHook) {
        this.orderDao = orderDao;
        this.marketDao = marketDao;
        this.resultDao = resultDao;
        this.taskDao = taskDao;
        this.indicatorFactory = indicatorFactory;
        this.summaryAggregator = summaryAggregator;
        this.t0DetailWriter = t0DetailWriter;
        this.transactionHook = transactionHook;
    }

    /**
     * 执行一个分片：计算全部母单指标，批次提交（结果 + 任务状态同事务），然后分层汇总。
     */
    public void execute(LocalDate tradeDate, int shardNo, int shardCount, String nodeId) {
        long start = System.currentTimeMillis();
        List<ParentOrderRow> orders = orderDao.loadShardOrders(tradeDate, shardNo, shardCount);
        if (orders.isEmpty()) {
            transactionHook.inBatchTransaction("shard-" + shardNo, () -> {
                taskDao.markSuccess(tradeDate, shardNo, nodeId);
                return null;
            });
            return;
        }
        // 共享行情数据本地缓存：当日分钟序列一次加载，分片内全部母单复用
        Map<String, MarketDao.SymbolStat> market = marketDao.loadDayStats(tradeDate);
        List<Long> orderIds = orders.stream().map(ParentOrderRow::orderId).toList();
        Map<Long, List<ChildOrderRow>> childrenMap = orderDao.loadChildren(orderIds);
        Map<Long, Long> versions = resultDao.loadCalcVersions(tradeDate, orderIds);

        Map<String, Integer> columnIdx = columnIndex();
        int columnCount = IndicatorResultRow.COLUMNS.length;
        List<IndicatorResultRow> rows = new ArrayList<>(orders.size());
        Set<String> touchedAlgos = new HashSet<>();
        for (ParentOrderRow order : orders) {
            List<ChildOrderRow> children = childrenMap.getOrDefault(order.orderId(), List.of());
            BigDecimal[] values = new BigDecimal[columnCount - 6]; // 指标列数（去掉 4 头部 + 2 尾部）
            for (IndicatorStrategy strategy : indicatorFactory.all()) {
                Integer idx = columnIdx.get(strategy.name());
                if (idx != null) {
                    values[idx] = strategy.compute(order, children, market);
                }
            }
            long version = versions.getOrDefault(order.orderId(), 0L) + 1;
            rows.add(toRow(order, values, version));
            if ("T0".equals(order.algoType())) {
                t0DetailWriter.upsert(order, children, version);
            }
            touchedAlgos.add(order.algoInstance());
        }

        // 批次提交：指标结果 + 任务状态在同一事务（full profile 由 Seata 保跨 schema 原子性）
        transactionHook.inBatchTransaction("shard-" + shardNo, () -> {
            resultDao.batchUpsert(rows);
            taskDao.markSuccess(tradeDate, shardNo, nodeId);
            return null;
        });

        // 分层汇总（分布式锁保护读-改-写）
        summaryAggregator.aggregate(tradeDate, touchedAlgos);
        log.info("[offline-calculate] 节点 {} 完成分片 {}（{} 母单，{}ms）",
                nodeId, shardNo, orders.size(), System.currentTimeMillis() - start);
    }

    private static IndicatorResultRow toRow(ParentOrderRow order, BigDecimal[] v, long version) {
        return new IndicatorResultRow(order.orderId(), order.tradeDate(), order.algoInstance(),
                order.clientName(),
                v[0], v[1], v[2], v[3], v[4], v[5], v[6],
                v[7], v[8], v[9], v[10], v[11],
                v[12], v[13], v[14], v[15], v[16], v[17],
                version, LocalDateTime.now());
    }

    /** 指标名 → 值数组序号（与 toRow 的参数序一致；COLUMNS 数组为唯一权威） */
    private static Map<String, Integer> columnIndex() {
        Map<String, Integer> idx = new HashMap<>();
        for (IndicatorStrategyMeta meta : INDICATORS) {
            idx.put(meta.name(), meta.index());
        }
        return idx;
    }

    private record IndicatorStrategyMeta(String name, int index) {
    }

    private static final List<IndicatorStrategyMeta> INDICATORS = List.of(
            new IndicatorStrategyMeta("vwap_perf_bps", 0),
            new IndicatorStrategyMeta("vwap_ot_perf_bps", 1),
            new IndicatorStrategyMeta("twap_perf_bps", 2),
            new IndicatorStrategyMeta("twap_ot_perf_bps", 3),
            new IndicatorStrategyMeta("arrival_perf_bps", 4),
            new IndicatorStrategyMeta("open_perf_bps", 5),
            new IndicatorStrategyMeta("close_perf_bps", 6),
            new IndicatorStrategyMeta("vwap_perf_pnl", 7),
            new IndicatorStrategyMeta("twap_perf_pnl", 8),
            new IndicatorStrategyMeta("arrival_perf_pnl", 9),
            new IndicatorStrategyMeta("open_perf_pnl", 10),
            new IndicatorStrategyMeta("close_perf_pnl", 11),
            new IndicatorStrategyMeta("cancel_ratio", 12),
            new IndicatorStrategyMeta("reject_ratio", 13),
            new IndicatorStrategyMeta("fill_ratio", 14),
            new IndicatorStrategyMeta("participation", 15),
            new IndicatorStrategyMeta("impact_bps", 16),
            new IndicatorStrategyMeta("exec_minutes", 17));
}
