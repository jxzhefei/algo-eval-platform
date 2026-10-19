package com.algoeval.storer;

import com.algoeval.source.sdk.QuoteListCodec;
import com.algoeval.source.sdk.SqlDialect;
import com.algoeval.source.sdk.TickEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 行情存储消费者：消费 Protobuf QuoteList 批量帧，攒批写入当日行情分表（逻辑表 market_tick，
 * 由 ShardingSphere DateShardingAlgorithm 按交易日路由到 market_tick_{yyyyMMdd}），幂等 upsert。
 */
@Component
public class MarketTickStorer {

    private static final Logger log = LoggerFactory.getLogger(MarketTickStorer.class);
    private static final String[] COLUMNS = {"symbol", "trade_date", "tick_time", "price", "volume", "turnover"};

    private final JdbcTemplate jdbcTemplate;
    private final SqlDialect dialect;
    private final AtomicLong stored = new AtomicLong();

    public MarketTickStorer(JdbcTemplate jdbcTemplate, SqlDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    @KafkaListener(topics = "${algo.kafka.topic.market:market-tick}", groupId = "data-storer-market",
            containerFactory = "batchKafkaListenerContainerFactory")
    public void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        List<TickEvent> ticks = new ArrayList<>(records.size() * 64);
        LocalDate tradeDate = null;
        for (ConsumerRecord<String, byte[]> record : records) {
            List<TickEvent> frame = QuoteListCodec.decode(record.value());
            ticks.addAll(frame);
            if (tradeDate == null && !frame.isEmpty()) {
                tradeDate = frame.get(0).tickTime().toLocalDate();
            }
        }
        if (!ticks.isEmpty()) {
            LocalDate date = tradeDate == null ? LocalDate.now() : tradeDate;
            String sql = dialect.upsert("market_tick", COLUMNS, 2);
            jdbcTemplate.batchUpdate(sql, ticks, 500, (ps, tick) -> {
                ps.setString(1, tick.symbol());
                ps.setDate(2, Date.valueOf(tick.tickTime().toLocalDate()));
                ps.setTimestamp(3, Timestamp.valueOf(tick.tickTime()));
                ps.setBigDecimal(4, tick.price());
                ps.setLong(5, tick.volume());
                ps.setBigDecimal(6, tick.turnover());
            });
            long total = stored.addAndGet(ticks.size());
            if (total % 50_000 < ticks.size()) {
                log.info("[data-storer] 行情已累计入库 {} 条（交易日 {}，分表路由由 ShardingSphere 承担）",
                        total, date);
            }
        }
        // 先入库再提交 offset：宕机重复消费由主键 upsert 幂等兜底
        ack.acknowledge();
    }

    public long storedCount() {
        return stored.get();
    }
}
