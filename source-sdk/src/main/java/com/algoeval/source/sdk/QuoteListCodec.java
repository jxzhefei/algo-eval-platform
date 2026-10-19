package com.algoeval.source.sdk;

import com.algoeval.source.sdk.proto.QuoteProtos;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 行情消息线格式的业务侧编解码：TickEvent 批 ↔ Protobuf Quote.QuoteList。
 * 金融数值以字符串承载（保 BigDecimal 精度，与真实系统 Quote 契约一致）。
 */
public final class QuoteListCodec {

    private QuoteListCodec() {
    }

    public static byte[] encode(List<TickEvent> batch, int batchSeq, int sourcePartition) {
        QuoteProtos.QuoteList.Builder list = QuoteProtos.QuoteList.newBuilder()
                .setBatchSeq(batchSeq)
                .setSourcePartition(sourcePartition);
        for (TickEvent tick : batch) {
            list.addItems(QuoteProtos.QuoteItem.newBuilder()
                    .setSymbol(tick.symbol())
                    .setTickTimeMillis(tick.tickTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    .setTradeDate(tick.tickTime().toLocalDate().toString())
                    .setPrice(tick.price().toPlainString())
                    .setVolume(tick.volume())
                    .setTurnover(tick.turnover().toPlainString()));
        }
        return list.build().toByteArray();
    }

    public static List<TickEvent> decode(byte[] data) {
        try {
            QuoteProtos.QuoteList list = QuoteProtos.QuoteList.parseFrom(data);
            List<TickEvent> ticks = new ArrayList<>(list.getItemsCount());
            for (QuoteProtos.QuoteItem item : list.getItemsList()) {
                LocalDateTime time = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(item.getTickTimeMillis()), ZoneId.systemDefault());
                ticks.add(new TickEvent(item.getSymbol(), time,
                        new BigDecimal(item.getPrice()), item.getVolume(),
                        new BigDecimal(item.getTurnover())));
            }
            return ticks;
        } catch (Exception e) {
            throw new IllegalArgumentException("QuoteList 反序列化失败", e);
        }
    }

    /** 批内首条行情的交易日（批量入库的路由依据） */
    public static LocalDate tradeDateOf(byte[] data) {
        try {
            QuoteProtos.QuoteList list = QuoteProtos.QuoteList.parseFrom(data);
            if (list.getItemsCount() == 0) {
                return null;
            }
            return LocalDate.parse(list.getItems(0).getTradeDate());
        } catch (Exception e) {
            throw new IllegalArgumentException("QuoteList 反序列化失败", e);
        }
    }
}
