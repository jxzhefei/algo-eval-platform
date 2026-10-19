package com.algoeval.offline.verify;

import com.algoeval.offline.dao.ResultDao;
import com.algoeval.offline.dao.SummaryDao;
import com.algoeval.offline.domain.IndicatorResultRow;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 结果快照与逐行对账：多节点结果集 == 单机基准（不重、不漏、不脏）。
 * update_time 字段天然不同，比对时排除。
 */
public record ResultSnapshot(List<IndicatorResultRow> results, List<SummaryDao.SummaryRow> summaries) {

    public static ResultSnapshot take(ResultDao resultDao, SummaryDao summaryDao, LocalDate tradeDate) {
        return new ResultSnapshot(resultDao.exportAll(tradeDate), summaryDao.findByDate(tradeDate));
    }

    /** @return null 表示完全一致；否则为差异描述 */
    public String diff(ResultSnapshot other) {
        if (this.results.size() != other.results.size()) {
            return "indicator_result 行数不等: " + this.results.size() + " vs " + other.results.size();
        }
        for (int i = 0; i < results.size(); i++) {
            IndicatorResultRow a = results.get(i);
            IndicatorResultRow b = other.results.get(i);
            if (a.orderId() != b.orderId()) {
                return "第 " + i + " 行 order_id 错位: " + a.orderId() + " vs " + b.orderId();
            }
            boolean same = Objects.equals(a.vwapPerfBps(), b.vwapPerfBps())
                    && Objects.equals(a.vwapOtPerfBps(), b.vwapOtPerfBps())
                    && Objects.equals(a.twapPerfBps(), b.twapPerfBps())
                    && Objects.equals(a.twapOtPerfBps(), b.twapOtPerfBps())
                    && Objects.equals(a.arrivalPerfBps(), b.arrivalPerfBps())
                    && Objects.equals(a.openPerfBps(), b.openPerfBps())
                    && Objects.equals(a.closePerfBps(), b.closePerfBps())
                    && Objects.equals(a.vwapPerfPnl(), b.vwapPerfPnl())
                    && Objects.equals(a.twapPerfPnl(), b.twapPerfPnl())
                    && Objects.equals(a.arrivalPerfPnl(), b.arrivalPerfPnl())
                    && Objects.equals(a.openPerfPnl(), b.openPerfPnl())
                    && Objects.equals(a.closePerfPnl(), b.closePerfPnl())
                    && Objects.equals(a.cancelRatio(), b.cancelRatio())
                    && Objects.equals(a.rejectRatio(), b.rejectRatio())
                    && Objects.equals(a.fillRatio(), b.fillRatio())
                    && Objects.equals(a.participation(), b.participation())
                    && Objects.equals(a.impactBps(), b.impactBps())
                    && Objects.equals(a.execMinutes(), b.execMinutes())
                    && a.calcVersion() == b.calcVersion();
            if (!same) {
                return "order_id=" + a.orderId() + " 指标值不等:\n  A=" + a + "\n  B=" + b;
            }
        }
        if (this.summaries.size() != other.summaries.size()) {
            return "algo_daily_summary 行数不等: " + this.summaries.size() + " vs " + other.summaries.size();
        }
        for (int i = 0; i < summaries.size(); i++) {
            SummaryDao.SummaryRow a = summaries.get(i);
            SummaryDao.SummaryRow b = other.summaries.get(i);
            boolean same = Objects.equals(a.algoInstance(), b.algoInstance())
                    && a.orderCount() == b.orderCount()
                    && Objects.equals(a.avgVwapBps(), b.avgVwapBps())
                    && Objects.equals(a.avgTwapBps(), b.avgTwapBps())
                    && Objects.equals(a.avgCancelRatio(), b.avgCancelRatio())
                    && Objects.equals(a.avgFillRatio(), b.avgFillRatio())
                    && Objects.equals(a.perfScore(), b.perfScore())
                    && Objects.equals(a.fillScore(), b.fillScore())
                    && Objects.equals(a.stabilityScore(), b.stabilityScore())
                    && Objects.equals(a.riskScore(), b.riskScore())
                    && Objects.equals(a.impactScore(), b.impactScore())
                    && Objects.equals(a.totalScore(), b.totalScore());
            if (!same) {
                return "汇总行 " + a.algoInstance() + " 不等:\n  A=" + a + "\n  B=" + b;
            }
        }
        return null;
    }
}
