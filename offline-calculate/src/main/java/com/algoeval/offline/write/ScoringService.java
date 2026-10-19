package com.algoeval.offline.write;

import com.algoeval.offline.dao.ResultDao;
import com.algoeval.offline.dao.SummaryDao;
import com.algoeval.offline.dao.TaskDao;
import com.algoeval.offline.domain.IndicatorResultRow;
import com.algoeval.offline.lock.DistributedLock;
import com.algoeval.offline.lock.DistributedLockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 五维评分模型（demo 口径，公式明确可复现）：
 * <pre>
 * 绩效     40%  max(0, 100 - 10×|avg_vwap_bps|)   偏离越小分越高
 * 完成度   20%  100 × fill_ratio
 * 稳定性   20%  100 / (1 + σ)                     σ=各母单 vwap 绩效标准差
 * 风险     10%  100 × (1 - cancel_ratio)
 * 市场冲击 10%  max(0, 100 - 20×|impact_bps|)
 * total_score = 0.4×绩效 + 0.2×完成度 + 0.2×稳定性 + 0.1×风险 + 0.1×冲击
 * </pre>
 * 生产口径涉及行业基线归一化，此处简化为绝对映射。
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final TaskDao taskDao;
    private final ResultDao resultDao;
    private final SummaryDao summaryDao;
    private final SummaryAggregator summaryAggregator;
    private final DistributedLockFactory lockFactory;
    private final com.algoeval.offline.dao.IndustryBaselineDao industryBaselineDao;
    private final T0DayAggregator t0DayAggregator;

    public ScoringService(TaskDao taskDao, ResultDao resultDao, SummaryDao summaryDao,
                          SummaryAggregator summaryAggregator, DistributedLockFactory lockFactory,
                          com.algoeval.offline.dao.IndustryBaselineDao industryBaselineDao,
                          T0DayAggregator t0DayAggregator) {
        this.taskDao = taskDao;
        this.resultDao = resultDao;
        this.summaryDao = summaryDao;
        this.summaryAggregator = summaryAggregator;
        this.lockFactory = lockFactory;
        this.industryBaselineDao = industryBaselineDao;
        this.t0DayAggregator = t0DayAggregator;
    }

    /**
     * 全部 32 分片完成后触发评分。多节点同时到达由评分锁收敛，且整体幂等
     * （评分是汇总数据的纯函数，重复执行结果一致）。
     *
     * @return 本次是否真正执行了评分
     */
    public boolean scoreIfComplete(LocalDate tradeDate) {
        if (!taskDao.allFinished(tradeDate)) {
            return false;
        }
        DistributedLock lock = lockFactory.get("lock:scoring:" + tradeDate);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5_000, 120_000);
            if (!acquired) {
                return false;
            }
            if (!summaryDao.scoresMissing(tradeDate)) {
                return false; // 已被其他节点评过
            }
            // 关键时序：allFinished 只保证明细已提交，不保证其他节点的汇总已写完。
            // 评分前先在评分锁内从明细全量重算汇总——评分输入必然完备且与分片完成顺序无关。
            Set<String> algos = summaryDao.findByDate(tradeDate).stream()
                    .map(SummaryDao.SummaryRow::algoInstance).collect(java.util.stream.Collectors.toSet());
            summaryAggregator.aggregate(tradeDate, algos);
            for (SummaryDao.SummaryRow summary : summaryDao.findByDate(tradeDate)) {
                List<IndicatorResultRow> rows = resultDao.findByAlgo(summary.algoInstance(), tradeDate);
                BigDecimal sigma = stddev(rows);
                BigDecimal avgImpact = meanImpact(rows);

                BigDecimal perf = clamp100(BigDecimal.valueOf(100)
                        .subtract(BigDecimal.TEN.multiply(abs(summary.avgVwapBps()))));
                BigDecimal fill = clamp100(BigDecimal.valueOf(100)
                        .multiply(nvl(summary.avgFillRatio())));
                BigDecimal stability = BigDecimal.valueOf(100)
                        .divide(BigDecimal.ONE.add(sigma), 2, RoundingMode.HALF_UP);
                BigDecimal risk = clamp100(BigDecimal.valueOf(100)
                        .multiply(BigDecimal.ONE.subtract(nvl(summary.avgCancelRatio()))));
                BigDecimal impact = clamp100(BigDecimal.valueOf(100)
                        .subtract(BigDecimal.valueOf(20).multiply(abs(avgImpact))));

                BigDecimal total = perf.multiply(new BigDecimal("0.4"))
                        .add(fill.multiply(new BigDecimal("0.2")))
                        .add(stability.multiply(new BigDecimal("0.2")))
                        .add(risk.multiply(new BigDecimal("0.1")))
                        .add(impact.multiply(new BigDecimal("0.1")))
                        .setScale(2, RoundingMode.HALF_UP);

                summaryDao.updateScores(summary.algoInstance(), tradeDate,
                        perf.setScale(2, RoundingMode.HALF_UP), fill.setScale(2, RoundingMode.HALF_UP),
                        stability, risk.setScale(2, RoundingMode.HALF_UP),
                        impact.setScale(2, RoundingMode.HALF_UP), total);
                log.info("[offline-calculate] 评分完成 {}: 绩效{} 完成度{} 稳定性{} 风险{} 冲击{} → 综合 {}",
                        summary.algoInstance(), perf.setScale(2, RoundingMode.HALF_UP),
                        fill.setScale(2, RoundingMode.HALF_UP), stability,
                        risk.setScale(2, RoundingMode.HALF_UP), impact.setScale(2, RoundingMode.HALF_UP), total);
            }
            // 评分后置（同一评分锁内，整体幂等）：行业均值基线 + T0 日汇总
            industryBaselineDao.refresh(tradeDate);
            t0DayAggregator.aggregate(tradeDate);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }

    /** 各母单 vwap 绩效的样本标准差 */
    private static BigDecimal stddev(List<IndicatorResultRow> rows) {
        List<BigDecimal> values = rows.stream().map(IndicatorResultRow::vwapPerfBps)
                .filter(java.util.Objects::nonNull).toList();
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal mean = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
        BigDecimal variance = values.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size() - 1), 6, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal meanImpact(List<IndicatorResultRow> rows) {
        List<BigDecimal> values = rows.stream().map(IndicatorResultRow::impactBps)
                .filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal abs(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.abs();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal clamp100(BigDecimal v) {
        return v.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
    }
}
