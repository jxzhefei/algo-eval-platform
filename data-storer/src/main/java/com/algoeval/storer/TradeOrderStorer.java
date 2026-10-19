package com.algoeval.storer;

import com.algoeval.ingest.model.ChildOrderMsg;
import com.algoeval.ingest.model.ParentOrderMsg;
import com.algoeval.ingest.model.TradeMsgCodec;
import com.algoeval.source.sdk.SqlDialect;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 交易数据存储消费者：母单/子单分类解析，攒批幂等 upsert。
 * 母单按 orderId 幂等覆盖（RUNNING→FINISHED 生命周期靠 upsert 收敛）；
 * T0 母单的子单同步投影为券商回报明细（to_broker_order_detail，真实表名）。
 */
@Component
public class TradeOrderStorer {

    private static final Logger log = LoggerFactory.getLogger(TradeOrderStorer.class);

    private static final String[] PARENT_COLUMNS = {"order_id", "trade_date", "algo_instance", "algo_type",
            "algo_vendor", "client_name", "account_id", "symbol", "side", "task_qty", "task_amount",
            "filled_qty", "filled_amount", "avg_fill_price", "arrival_price", "start_time", "end_time",
            "status", "update_time"};
    private static final String[] CHILD_COLUMNS = {"child_id", "order_id", "trade_date", "side", "exec_time",
            "qty", "price", "filled_qty", "avg_fill_price", "is_cancelled", "is_rejected"};
    private static final String[] BROKER_COLUMNS = {"child_id", "order_id", "trade_date", "side", "exec_time",
            "qty", "price", "filled_qty", "avg_fill_price", "is_cancelled", "is_rejected", "report_time"};

    private final JdbcTemplate jdbcTemplate;
    private final SqlDialect dialect;
    /** order → algoType（T0 回报投影判定），跨批维护 */
    private final Map<Long, String> orderAlgoTypes = new ConcurrentHashMap<>();
    private final AtomicLong parentsStored = new AtomicLong();
    private final AtomicLong childrenStored = new AtomicLong();

    public TradeOrderStorer(JdbcTemplate jdbcTemplate, SqlDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    @KafkaListener(topics = "${algo.kafka.topic.trade:trade-order}", groupId = "data-storer-trade",
            containerFactory = "batchKafkaListenerContainerFactory")
    public void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        List<ParentOrderMsg> parents = new ArrayList<>();
        List<ChildOrderMsg> children = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            if (TradeMsgCodec.isParent(record.value())) {
                parents.add(TradeMsgCodec.decodeParent(record.value()));
            } else {
                children.add(TradeMsgCodec.decodeChild(record.value()));
            }
        }
        if (!parents.isEmpty()) {
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            jdbcTemplate.batchUpdate(dialect.upsert("parent_order", PARENT_COLUMNS, 2), parents, 500,
                    (ps, p) -> {
                        ps.setLong(1, p.orderId());
                        ps.setDate(2, Date.valueOf(p.tradeDate()));
                        ps.setString(3, p.algoInstance());
                        ps.setString(4, p.algoType());
                        ps.setString(5, p.algoVendor());
                        ps.setString(6, p.clientName());
                        ps.setString(7, p.accountId());
                        ps.setString(8, p.symbol());
                        ps.setString(9, p.side());
                        ps.setLong(10, p.taskQty());
                        ps.setBigDecimal(11, p.taskAmount());
                        ps.setLong(12, p.filledQty());
                        ps.setBigDecimal(13, p.filledAmount());
                        if (p.avgFillPrice() != null) {
                            ps.setBigDecimal(14, p.avgFillPrice());
                        } else {
                            ps.setNull(14, java.sql.Types.DECIMAL);
                        }
                        if (p.arrivalPrice() != null) {
                            ps.setBigDecimal(15, p.arrivalPrice());
                        } else {
                            ps.setBigDecimal(15, java.math.BigDecimal.ZERO);
                        }
                        if (p.startTime() != null) {
                            ps.setTimestamp(16, Timestamp.valueOf(p.startTime()));
                        } else {
                            ps.setNull(16, java.sql.Types.TIMESTAMP);
                        }
                        if (p.endTime() != null) {
                            ps.setTimestamp(17, Timestamp.valueOf(p.endTime()));
                        } else {
                            ps.setNull(17, java.sql.Types.TIMESTAMP);
                        }
                        ps.setString(18, p.status());
                        ps.setTimestamp(19, now);
                    });
            for (ParentOrderMsg p : parents) {
                orderAlgoTypes.put(p.orderId(), p.algoType());
            }
        }
        if (!children.isEmpty()) {
            jdbcTemplate.batchUpdate(dialect.upsert("child_order", CHILD_COLUMNS, 2), children, 500, (ps, c) -> {
                ps.setLong(1, c.childId());
                ps.setLong(2, c.orderId());
                ps.setDate(3, Date.valueOf(c.execTime().toLocalDate()));
                ps.setString(4, c.side());
                ps.setTimestamp(5, Timestamp.valueOf(c.execTime()));
                ps.setLong(6, c.qty());
                if (c.price() != null) {
                    ps.setBigDecimal(7, c.price());
                } else {
                    ps.setBigDecimal(7, java.math.BigDecimal.ZERO);
                }
                ps.setLong(8, c.filledQty());
                if (c.avgFillPrice() != null) {
                    ps.setBigDecimal(9, c.avgFillPrice());
                } else {
                    ps.setNull(9, java.sql.Types.DECIMAL);
                }
                ps.setInt(10, c.cancelled() ? 1 : 0);
                ps.setInt(11, c.rejected() ? 1 : 0);
            });
            upsertBrokerReports(children);
        }
        long pTotal = parentsStored.addAndGet(parents.size());
        long cTotal = childrenStored.addAndGet(children.size());
        if (cTotal % 5000 < children.size() + parents.size()) {
            log.info("[data-storer] 交易数据已累计入库 母单 {} / 子单 {}", pTotal, cTotal);
        }
        ack.acknowledge();
    }

    /** T0 母单的子单投影为券商侧回报明细（真实表 to_broker_order_detail） */
    private void upsertBrokerReports(List<ChildOrderMsg> children) {
        List<ChildOrderMsg> t0Children = new ArrayList<>();
        for (ChildOrderMsg c : children) {
            if ("T0".equals(orderAlgoTypes.get(c.orderId()))) {
                t0Children.add(c);
            }
        }
        if (t0Children.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.batchUpdate(dialect.upsert("to_broker_order_detail", BROKER_COLUMNS, 2),
                t0Children, 500, (ps, c) -> {
                    ps.setLong(1, c.childId());
                    ps.setLong(2, c.orderId());
                    ps.setDate(3, Date.valueOf(c.execTime().toLocalDate()));
                    ps.setString(4, c.side());
                    ps.setTimestamp(5, Timestamp.valueOf(c.execTime()));
                    ps.setLong(6, c.qty());
                    ps.setBigDecimal(7, c.price() == null ? java.math.BigDecimal.ZERO : c.price());
                    ps.setLong(8, c.filledQty());
                    if (c.avgFillPrice() != null) {
                        ps.setBigDecimal(9, c.avgFillPrice());
                    } else {
                        ps.setNull(9, java.sql.Types.DECIMAL);
                    }
                    ps.setInt(10, c.cancelled() ? 1 : 0);
                    ps.setInt(11, c.rejected() ? 1 : 0);
                    ps.setTimestamp(12, now);
                });
    }
}
