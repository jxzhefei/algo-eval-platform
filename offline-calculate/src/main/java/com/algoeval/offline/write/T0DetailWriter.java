package com.algoeval.offline.write;

import com.algoeval.offline.dao.OrderDao;
import com.algoeval.offline.domain.ChildOrderRow;
import com.algoeval.offline.domain.ParentOrderRow;
import com.algoeval.source.sdk.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * T0 母单明细写入（真实表 t0_order_detail）：从子单还原回转交易结构——
 * 买入额/卖出额/回转次数/净收益/收益率/未平仓标记。T0 双轨评价（概览/画像）的数据底座。
 */
@Component
public class T0DetailWriter {

    private static final Logger log = LoggerFactory.getLogger(T0DetailWriter.class);

    private static final String[] COLUMNS = {"order_id", "trade_date", "algo_instance", "client_name", "symbol",
            "side", "buy_amount", "sell_amount", "buy_qty", "sell_qty", "round_trip_cnt", "net_pnl",
            "return_bps", "unclosed_flag", "calc_version", "update_time"};

    private final JdbcTemplate jdbcTemplate;
    private final SqlDialect dialect;

    public T0DetailWriter(JdbcTemplate jdbcTemplate, SqlDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    /** 幂等 upsert 一笔 T0 母单的回转明细 */
    public void upsert(ParentOrderRow order, List<ChildOrderRow> children, long calcVersion) {
        BigDecimal buyAmount = BigDecimal.ZERO;
        BigDecimal sellAmount = BigDecimal.ZERO;
        long buyQty = 0;
        long sellQty = 0;
        int buyLegs = 0;
        int sellLegs = 0;
        for (ChildOrderRow c : children) {
            if (c.filledQty() <= 0 || c.avgFillPrice() == null) {
                continue;
            }
            BigDecimal amount = c.avgFillPrice().multiply(BigDecimal.valueOf(c.filledQty()));
            if ("BUY".equals(c.side())) {
                buyAmount = buyAmount.add(amount);
                buyQty += c.filledQty();
                buyLegs++;
            } else {
                sellAmount = sellAmount.add(amount);
                sellQty += c.filledQty();
                sellLegs++;
            }
        }
        BigDecimal netPnl = sellAmount.subtract(buyAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal returnBps = buyAmount.signum() == 0 ? BigDecimal.ZERO
                : netPnl.multiply(BigDecimal.valueOf(10_000))
                        .divide(buyAmount, 4, RoundingMode.HALF_UP);
        int roundTrips = Math.min(buyLegs, sellLegs);
        int unclosed = (buyQty == sellQty) ? 0 : 1;

        jdbcTemplate.update(dialect.upsert("t0_order_detail", COLUMNS, 2),
                order.orderId(), java.sql.Date.valueOf(order.tradeDate()), order.algoInstance(),
                order.clientName(), order.symbol(), order.side(),
                buyAmount.setScale(2, RoundingMode.HALF_UP), sellAmount.setScale(2, RoundingMode.HALF_UP),
                buyQty, sellQty, roundTrips, netPnl, returnBps, unclosed, calcVersion,
                Timestamp.valueOf(LocalDateTime.now()));
    }
}
