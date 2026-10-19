package com.algoeval.ingest;

import com.algoeval.ingest.model.ParentOrderMsg;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 第三方算法商报文归一化（对应真实系统 atx-third-guolian）：
 * 供应商各自的字段命名/方向编码/时间格式 → 平台统一母单模型。
 * 第三方任务号为字符串，映射到平台内部数值主键（独立号段，避免与我方号段冲突）。
 */
@Component
public class ThirdPartyNormalizer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 第三方母单独立号段 */
    private static final long THIRD_PARTY_ID_BASE = 900_000_000L;
    private final AtomicLong sequence = new AtomicLong();

    /** 第三方算法商上报报文（异构契约示例：字段命名/方向编码与平台模型不同） */
    public record ThirdPartyOrder(String vendorCode,       // 供应商标识
                                  String taskNo,           // 第三方任务号
                                  String algoName,         // TWAP/VWAP/T0
                                  String fundAccount,      // 资金账号
                                  String institutionName,  // 机构客户名
                                  String securityCode,     // 证券代码
                                  char direction,          // 'B'/'S'
                                  long orderQty,
                                  BigDecimal orderAmount,
                                  BigDecimal arrivalPx,    // 到达时刻市场价
                                  String entryTime,        // yyyy-MM-dd HH:mm:ss
                                  String deadlineTime) {
    }

    public ParentOrderMsg normalize(ThirdPartyOrder o) {
        if (o == null || o.taskNo() == null || o.vendorCode() == null) {
            throw new IllegalArgumentException("taskNo/vendorCode 必填");
        }
        LocalDateTime start = o.entryTime() == null ? null : LocalDateTime.parse(o.entryTime(), TIME);
        LocalDateTime end = o.deadlineTime() == null ? null : LocalDateTime.parse(o.deadlineTime(), TIME);
        LocalDate tradeDate = start == null ? LocalDate.now() : start.toLocalDate();
        String algoInstance = (o.vendorCode() + "_" + o.algoName()).toLowerCase();
        return new ParentOrderMsg(
                THIRD_PARTY_ID_BASE + sequence.incrementAndGet(),
                algoInstance,
                o.algoName() == null ? "TWAP" : o.algoName().toUpperCase(),
                o.vendorCode(),
                o.institutionName() == null ? o.fundAccount() : o.institutionName(),
                o.fundAccount(),
                o.securityCode(),
                tradeDate,
                o.direction() == 'S' ? "SELL" : "BUY",
                o.orderQty(),
                o.orderAmount(),
                0, BigDecimal.ZERO, null,
                o.arrivalPx(),
                start, end, "RUNNING");
    }
}
