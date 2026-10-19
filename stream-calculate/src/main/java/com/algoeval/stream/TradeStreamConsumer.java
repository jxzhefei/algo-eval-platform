package com.algoeval.stream;

import com.algoeval.ingest.model.ChildOrderMsg;
import com.algoeval.ingest.model.ParentOrderMsg;
import com.algoeval.ingest.model.TradeMsgCodec;
import com.algoeval.source.sdk.SqlDialect;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 实时计算消费者：消费 trade-order 流 → 滑动窗口实时绩效 →
 * realtime_metric 落表 + WebSocket 推送；撤单率超阈值触发告警。
 */
@Component
public class TradeStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradeStreamConsumer.class);
    private static final String[] RT_COLUMNS = {"algo_instance", "window_time", "running_vwap_bps",
            "cancel_ratio", "active_orders", "alert_flag"};

    private final SlidingWindowTracker tracker;
    private final JdbcTemplate jdbcTemplate;
    private final SqlDialect dialect;

    @Value("${algo.stream.cancel-alert-threshold:0.25}")
    private double cancelAlertThreshold;

    public TradeStreamConsumer(SlidingWindowTracker tracker, JdbcTemplate jdbcTemplate, SqlDialect dialect) {
        this.tracker = tracker;
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    @KafkaListener(topics = "${algo.kafka.topic.trade:trade-order}", groupId = "stream-calculate",
            containerFactory = "batchKafkaListenerContainerFactory")
    public void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        long now = System.nanoTime();
        for (ConsumerRecord<String, byte[]> record : records) {
            if (TradeMsgCodec.isParent(record.value())) {
                tracker.onParent(TradeMsgCodec.decodeParent(record.value()));
            } else {
                tracker.onChild(TradeMsgCodec.decodeChild(record.value()), now);
            }
        }
        ack.acknowledge();
    }

    /** 每 5 秒输出一轮窗口指标（演示节奏压缩：日内 4 分钟演完） */
    @Scheduled(fixedRate = 5000, initialDelay = 5000)
    public void emitWindowMetrics() {
        if (tracker.algoInstances().isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        String upsert = dialect.upsert("realtime_metric", RT_COLUMNS, 2);
        for (String algo : tracker.algoInstances()) {
            SlidingWindowTracker.Snapshot snap = tracker.snapshot(algo, now);
            if (snap == null || snap.lastMarketTime() == null) {
                continue;
            }
            LocalDateTime windowTime = snap.lastMarketTime().withSecond(0).withNano(0);
            boolean alert = snap.cancelRatio().doubleValue() > cancelAlertThreshold;
            jdbcTemplate.update(upsert, algo, Timestamp.valueOf(windowTime), snap.runningVwapBps(),
                    snap.cancelRatio(), snap.activeOrders(), alert ? 1 : 0);

            String metricJson = String.format(
                    "{\"type\":\"metric\",\"algo\":\"%s\",\"windowTime\":\"%s\",\"runningVwapBps\":%s,"
                            + "\"cancelRatio\":%s,\"activeOrders\":%d}",
                    algo, windowTime, snap.runningVwapBps(), snap.cancelRatio(), snap.activeOrders());
            IntradayWebSocketConfig.IntradayWebSocketHandler.broadcast(metricJson);

            if (alert) {
                log.warn("[stream-calculate] 告警：{} 窗口撤单率 {} 超阈值 {}", algo, snap.cancelRatio(),
                        cancelAlertThreshold);
                IntradayWebSocketConfig.IntradayWebSocketHandler.broadcast(String.format(
                        "{\"type\":\"alert\",\"algo\":\"%s\",\"windowTime\":\"%s\",\"cancelRatio\":%s,"
                                + "\"message\":\"撤单率突增超阈值\"}",
                        algo, windowTime, snap.cancelRatio()));
            }
        }
    }
}
