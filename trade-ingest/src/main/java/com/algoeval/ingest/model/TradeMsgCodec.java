package com.algoeval.ingest.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交易消息线格式编解码（生产/消费两侧的序列化契约）。
 * 自研紧凑分隔符格式（ByteArray 传输，绕开框架序列化开销——对齐真实系统交易 topic 编码）。
 * 行情 topic 走 Protobuf QuoteList（见 source-sdk QuoteListCodec），交易 topic 走本契约。
 */
public final class TradeMsgCodec {

    private static final String SEP = "\\|";
    private static final String RAW_SEP = "|";

    private TradeMsgCodec() {
    }

    public static byte[] encodeParent(ParentOrderMsg p) {
        String s = "P" + RAW_SEP + p.orderId() + RAW_SEP + p.algoInstance() + RAW_SEP + p.algoType()
                + RAW_SEP + p.algoVendor() + RAW_SEP + p.clientName() + RAW_SEP + p.accountId()
                + RAW_SEP + p.symbol() + RAW_SEP + p.tradeDate() + RAW_SEP + p.side()
                + RAW_SEP + p.taskQty() + RAW_SEP + p.taskAmount().toPlainString()
                + RAW_SEP + p.filledQty() + RAW_SEP + p.filledAmount().toPlainString()
                + RAW_SEP + (p.avgFillPrice() == null ? "" : p.avgFillPrice().toPlainString())
                + RAW_SEP + (p.arrivalPrice() == null ? "" : p.arrivalPrice().toPlainString())
                + RAW_SEP + (p.startTime() == null ? "" : p.startTime())
                + RAW_SEP + (p.endTime() == null ? "" : p.endTime())
                + RAW_SEP + p.status();
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeChild(ChildOrderMsg c) {
        String s = "C" + RAW_SEP + c.childId() + RAW_SEP + c.orderId() + RAW_SEP + c.side()
                + RAW_SEP + c.execTime() + RAW_SEP + c.qty()
                + RAW_SEP + (c.price() == null ? "" : c.price().toPlainString())
                + RAW_SEP + c.filledQty()
                + RAW_SEP + (c.avgFillPrice() == null ? "" : c.avgFillPrice().toPlainString())
                + RAW_SEP + (c.cancelled() ? 1 : 0)
                + RAW_SEP + (c.rejected() ? 1 : 0);
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static boolean isParent(byte[] data) {
        return data.length > 0 && data[0] == 'P';
    }

    public static ParentOrderMsg decodeParent(byte[] data) {
        String[] p = new String(data, StandardCharsets.UTF_8).split(SEP, -1);
        return new ParentOrderMsg(Long.parseLong(p[1]), p[2], p[3], p[4], p[5], p[6], p[7],
                LocalDate.parse(p[8]), p[9], Long.parseLong(p[10]), new BigDecimal(p[11]),
                Long.parseLong(p[12]), new BigDecimal(p[13]),
                p[14].isEmpty() ? null : new BigDecimal(p[14]),
                p[15].isEmpty() ? null : new BigDecimal(p[15]),
                p[16].isEmpty() ? null : LocalDateTime.parse(p[16]),
                p[17].isEmpty() ? null : LocalDateTime.parse(p[17]),
                p[18]);
    }

    public static ChildOrderMsg decodeChild(byte[] data) {
        String[] p = new String(data, StandardCharsets.UTF_8).split(SEP, -1);
        return new ChildOrderMsg(Long.parseLong(p[1]), Long.parseLong(p[2]), p[3],
                LocalDateTime.parse(p[4]), Long.parseLong(p[5]),
                p[6].isEmpty() ? null : new BigDecimal(p[6]),
                Long.parseLong(p[7]),
                p[8].isEmpty() ? null : new BigDecimal(p[8]),
                "1".equals(p[9]), "1".equals(p[10]));
    }
}
