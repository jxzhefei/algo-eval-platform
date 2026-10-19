package com.algoeval.offline.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 母单级指标结果（宽表，一母单一行）。
 * 绩效基准 7 种（VWAP/TWAP 含委托时间变体、Arrival、开盘、收盘），BPS 与 PNL 双口径
 * ——对齐产品实证的指标命名（算法画像分桶表/算法概览表列名）。
 */
public record IndicatorResultRow(long orderId,
                                 LocalDate tradeDate,
                                 String algoInstance,
                                 String clientName,
                                 BigDecimal vwapPerfBps,
                                 BigDecimal vwapOtPerfBps,
                                 BigDecimal twapPerfBps,
                                 BigDecimal twapOtPerfBps,
                                 BigDecimal arrivalPerfBps,
                                 BigDecimal openPerfBps,
                                 BigDecimal closePerfBps,
                                 BigDecimal vwapPerfPnl,
                                 BigDecimal twapPerfPnl,
                                 BigDecimal arrivalPerfPnl,
                                 BigDecimal openPerfPnl,
                                 BigDecimal closePerfPnl,
                                 BigDecimal cancelRatio,
                                 BigDecimal rejectRatio,
                                 BigDecimal fillRatio,
                                 BigDecimal participation,
                                 BigDecimal impactBps,
                                 BigDecimal execMinutes,
                                 long calcVersion,
                                 LocalDateTime updateTime) {

    public static final String[] COLUMNS = {"order_id", "trade_date", "algo_instance", "client_name",
            "vwap_perf_bps", "vwap_ot_perf_bps", "twap_perf_bps", "twap_ot_perf_bps",
            "arrival_perf_bps", "open_perf_bps", "close_perf_bps",
            "vwap_perf_pnl", "twap_perf_pnl", "arrival_perf_pnl", "open_perf_pnl", "close_perf_pnl",
            "cancel_ratio", "reject_ratio", "fill_ratio", "participation", "impact_bps", "exec_minutes",
            "calc_version", "update_time"};
}
