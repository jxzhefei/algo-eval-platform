package com.algoeval.offline.write;

import com.algoeval.offline.dao.ResultDao;
import com.algoeval.offline.dao.SummaryDao;
import com.algoeval.offline.domain.IndicatorResultRow;
import com.algoeval.offline.lock.DistributedLock;
import com.algoeval.offline.lock.DistributedLockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 分层汇总：同一算法实例的母单散落在不同分片 → 多节点同时"读-改-写"同一行
 * 算法实例×交易日汇总记录会 lost update → 分布式锁串行化冲突域。
 * <p>
 * 合并语义：锁内从明细表按固定排序（order_id）重算均值——BigDecimal 求和顺序确定，
 * 最终结果与分片完成顺序无关（多节点 vs 单机逐行对账相等的前提）。
 */
@Component
public class SummaryAggregator {

    private static final Logger log = LoggerFactory.getLogger(SummaryAggregator.class);

    private final ResultDao resultDao;
    private final SummaryDao summaryDao;
    private final DistributedLockFactory lockFactory;

    public SummaryAggregator(ResultDao resultDao, SummaryDao summaryDao, DistributedLockFactory lockFactory) {
        this.resultDao = resultDao;
        this.summaryDao = summaryDao;
        this.lockFactory = lockFactory;
    }

    /** 对分片触及的算法实例逐个做锁内汇总 */
    public void aggregate(LocalDate tradeDate, Set<String> algoInstances) {
        for (String algo : algoInstances) {
            DistributedLock lock = lockFactory.get("lock:summary:" + algo + ":" + tradeDate);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(5_000, 60_000);
                if (!acquired) {
                    log.warn("[offline-calculate] 汇总锁获取超时，跳过本轮（下轮分片完成时会再触发）: {}", algo);
                    continue;
                }
                // 锁内读-改-写：从明细重算全量均值
                List<IndicatorResultRow> rows = resultDao.findByAlgo(algo, tradeDate);
                if (rows.isEmpty()) {
                    continue;
                }
                summaryDao.upsertSummary(algo, tradeDate, rows.size(),
                        mean(rows, IndicatorResultRow::vwapPerfBps),
                        mean(rows, IndicatorResultRow::twapPerfBps),
                        mean(rows, IndicatorResultRow::cancelRatio),
                        mean(rows, IndicatorResultRow::fillRatio));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("汇总锁等待被中断", e);
            } finally {
                if (acquired) {
                    lock.unlock();
                }
            }
        }
    }

    private static BigDecimal mean(List<IndicatorResultRow> rows,
                                   Function<IndicatorResultRow, BigDecimal> getter) {
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (IndicatorResultRow row : rows) {
            BigDecimal v = getter.apply(row);
            if (v != null) {
                sum = sum.add(v);
                n++;
            }
        }
        return n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }
}
