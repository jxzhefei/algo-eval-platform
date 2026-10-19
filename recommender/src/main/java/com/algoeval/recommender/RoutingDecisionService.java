package com.algoeval.recommender;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能路由决策引擎（v1 统计版）。
 * <p>
 * 输入新母单要素（标的/金额/方向）→ 匹配能力画像矩阵 → 收缩加权评分 →
 * 输出推荐算法 + 可解释理由（每条理由都带样本数与数值——可解释性是金融风控红线）。
 * <p>
 * 决策落库 route_decision，盘后与实际评价结果回流比对——数据飞轮。
 */
@Service
public class RoutingDecisionService {

    private final CapabilityMatrixService matrixService;
    private final RouteDecisionDao decisionDao;

    @Value("${algo.recommend.lookback-days:90}")
    private int lookbackDays;

    public RoutingDecisionService(CapabilityMatrixService matrixService, RouteDecisionDao decisionDao) {
        this.matrixService = matrixService;
        this.decisionDao = decisionDao;
    }

    /** 路由结果 */
    public record RouteResult(LocalDate tradeDate, String symbol, BigDecimal amount, String side,
                              String liquidityBucket, String scaleBucket, BigDecimal estimatedParticipation,
                              long decisionId, Candidate recommended, List<Candidate> alternatives,
                              BigDecimal confidence, List<String> reasons, int matrixSampleSize) {
    }

    /** 候选算法 */
    public record Candidate(String algoInstance, BigDecimal expectedVwapBps, BigDecimal expectedScore,
                            int sampleCount, boolean exactMatch) {
    }

    /**
     * 对一笔新母单做路由决策。
     *
     * @param symbol 标的
     * @param amount 任务金额（元）
     * @param side   BUY / SELL
     */
    public RouteResult route(String symbol, BigDecimal amount, String side, LocalDate tradeDate) {
        LocalDate to = tradeDate == null ? LocalDate.now() : tradeDate;
        LocalDate from = to.minusDays(lookbackDays);

        // 1. 标的特征定位：当日成交额 → 流动性桶；金额/成交额 → 估算参与率 → 规模桶
        BigDecimal dayTurnover = matrixService.symbolDayTurnover(to, symbol);
        String liqBucket = CapabilityMatrixService.liquidityBucket(dayTurnover);
        BigDecimal participation = null;
        if (dayTurnover != null && dayTurnover.signum() > 0) {
            participation = amount.divide(dayTurnover, 6, RoundingMode.HALF_UP);
        }
        String scaleBucket = CapabilityMatrixService.scaleBucket(participation);

        // 2. 能力矩阵 + 期望评估
        Map<String, List<CapabilityMatrixService.CellStat>> matrix = matrixService.buildMatrix(from, to);
        List<CapabilityMatrixService.AlgoExpectation> expectations =
                matrixService.expectations(matrix, liqBucket, scaleBucket, side);

        if (expectations.isEmpty()) {
            throw new IllegalStateException("评价数据尚未产出（离线计算未完成），无法路由");
        }
        int matrixSampleSize = expectations.stream().mapToInt(CapabilityMatrixService.AlgoExpectation::sampleCount).sum();

        // 3. 排序：期望综合得分降序，同分按期望 VWAP 绩效升序（bps 越小越好）
        expectations.sort(Comparator
                .comparing(CapabilityMatrixService.AlgoExpectation::expectedScore).reversed()
                .thenComparing(CapabilityMatrixService.AlgoExpectation::expectedVwapBps));
        CapabilityMatrixService.AlgoExpectation best = expectations.get(0);
        CapabilityMatrixService.AlgoExpectation second = expectations.size() > 1 ? expectations.get(1) : null;

        List<Candidate> candidates = expectations.stream()
                .map(e -> new Candidate(e.algoInstance(), e.expectedVwapBps(), e.expectedScore(),
                        e.sampleCount(), e.exactMatch()))
                .toList();

        // 4. 可解释理由（为什么推荐它——每个数字都可追溯到历史评价数据）
        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("标的 %s 当日成交额 %s 元，流动性分桶「%s」；估算参与率 %s，规模分桶「%s」。",
                symbol, dayTurnover == null ? "未知" : dayTurnover.toPlainString(), liqBucket,
                participation == null ? "未知" : participation.toPlainString() + "（" + scaleBucket + "）", scaleBucket));
        if (best.exactMatch()) {
            reasons.add(String.format("矩阵精确命中：近 %d 日「%s × %s × %s」口径下，%s 平均 VWAP 绩效 %s bps（样本 %d 笔）。",
                    lookbackDays, liqBucket, scaleBucket, side, best.algoInstance(),
                    best.expectedVwapBps().toPlainString(), best.sampleCount()));
        } else {
            reasons.add(String.format("矩阵未精确命中该口径，采用收缩估计（桶内样本向全局均值收缩）：%s 期望 VWAP 绩效 %s bps（有效样本 %d 笔）。",
                    best.algoInstance(), best.expectedVwapBps().toPlainString(), best.sampleCount()));
        }
        if (second != null) {
            reasons.add(String.format("对比第二名 %s（期望绩效 %s bps，综合分 %s）：%s 综合分 %s，预期优势 %s 分。",
                    second.algoInstance(), second.expectedVwapBps().toPlainString(),
                    second.expectedScore().toPlainString(), best.algoInstance(),
                    best.expectedScore().toPlainString(),
                    best.expectedScore().subtract(second.expectedScore()).toPlainString()));
        }
        reasons.add(String.format("完成率期望 %s，撤单率期望 %s——五维加权口径（绩效40%%/完成20%%/稳定20%%/风险10%%/冲击10%%）。",
                best.expectedFillRatio().toPlainString(), best.expectedCancelRatio().toPlainString()));

        // 5. 置信度：精确命中 + 样本量 → 0~1
        double conf = Math.min(1.0, best.sampleCount() / 200.0) * (best.exactMatch() ? 1.0 : 0.6);
        BigDecimal confidence = BigDecimal.valueOf(conf).setScale(2, RoundingMode.HALF_UP);

        // 6. 决策落库（闭环回流的起点）
        long decisionId = decisionDao.save(to, symbol, amount, side, liqBucket, scaleBucket,
                best.algoInstance(), second == null ? null : second.algoInstance(),
                best.expectedVwapBps(), best.expectedScore(), confidence, String.join(" ", reasons));

        List<Candidate> alternatives = candidates.stream()
                .filter(c -> !c.algoInstance().equals(best.algoInstance())).toList();
        return new RouteResult(to, symbol, amount, side, liqBucket, scaleBucket, participation,
                decisionId, candidates.get(0), alternatives, confidence, reasons, matrixSampleSize);
    }

    /** 能力矩阵视图（前端热力图）：算法 × 规模分桶 → 平均 VWAP 绩效 */
    public Map<String, Object> matrixView(LocalDate from, LocalDate to) {
        Map<String, List<CapabilityMatrixService.CellStat>> matrix = matrixService.buildMatrix(from, to);
        List<Map<String, Object>> cells = new ArrayList<>();
        for (List<CapabilityMatrixService.CellStat> stats : matrix.values()) {
            for (CapabilityMatrixService.CellStat s : stats) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("algoInstance", s.algoInstance());
                cell.put("liquidityBucket", s.liquidityBucket());
                cell.put("scaleBucket", s.scaleBucket());
                cell.put("side", s.side());
                cell.put("orderCount", s.orderCount());
                cell.put("avgVwapBps", s.avgVwapBps());
                cell.put("avgCancelRatio", s.avgCancelRatio());
                cell.put("avgFillRatio", s.avgFillRatio());
                cell.put("score", s.score());
                cells.add(cell);
            }
        }
        return Map.of("from", from.toString(), "to", to.toString(), "cells", cells);
    }
}
