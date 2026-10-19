package com.algoeval.source.sdk;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * tick 消息线格式编解码（生产/消费两侧的序列化契约）。
 * 紧凑分隔符格式，零依赖；真实系统此处为 Protobuf。
 */
public final class TickCodec {

    private static final String SEP = "|";

    private TickCodec() {
    }

    public static byte[] encode(TickEvent tick) {
        String s = tick.symbol() + SEP + tick.tickTime() + SEP + tick.price().toPlainString()
                + SEP + tick.volume() + SEP + tick.turnover().toPlainString();
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static TickEvent decode(byte[] data) {
        String[] parts = new String(data, StandardCharsets.UTF_8).split("\\|");
        return new TickEvent(parts[0], LocalDateTime.parse(parts[1]), new BigDecimal(parts[2]),
                Long.parseLong(parts[3]), new BigDecimal(parts[4]));
    }
}
