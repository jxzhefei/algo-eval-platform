package com.algoeval.offline.dao;

import com.algoeval.offline.domain.ChildOrderRow;
import com.algoeval.offline.domain.ParentOrderRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单数据访问：分片加载母单 + 批量加载子单（IN 分块，避免逐单点查）。
 * SQL 全部走逻辑表名（parent_order/child_order），
 * 分表路由由 ShardingSphere DateShardingAlgorithm 按 trade_date 承担。
 */
@Repository
public class OrderDao {

    private static final int IN_CHUNK = 500;

    private final JdbcTemplate jdbcTemplate;

    public OrderDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 分片规则是纯函数：MOD(order_id, shardCount) = shardNo，任何节点算出同样归属 */
    public List<ParentOrderRow> loadShardOrders(LocalDate tradeDate, int shardNo, int shardCount) {
        return jdbcTemplate.query(
                "SELECT order_id, algo_instance, algo_type, algo_vendor, client_name, account_id, symbol, "
                        + "trade_date, side, task_qty, task_amount, filled_qty, filled_amount, avg_fill_price, "
                        + "arrival_price, start_time, end_time, status "
                        + "FROM parent_order WHERE trade_date=? AND MOD(order_id, ?)=? ORDER BY order_id",
                (rs, i) -> new ParentOrderRow(rs.getLong("order_id"), rs.getString("algo_instance"),
                        rs.getString("algo_type"), rs.getString("algo_vendor"), rs.getString("client_name"),
                        rs.getString("account_id"), rs.getString("symbol"),
                        rs.getDate("trade_date").toLocalDate(), rs.getString("side"), rs.getLong("task_qty"),
                        rs.getBigDecimal("task_amount"), rs.getLong("filled_qty"),
                        rs.getBigDecimal("filled_amount"), rs.getBigDecimal("avg_fill_price"),
                        rs.getBigDecimal("arrival_price"),
                        rs.getTimestamp("start_time") == null ? null
                                : rs.getTimestamp("start_time").toLocalDateTime(),
                        rs.getTimestamp("end_time") == null ? null
                                : rs.getTimestamp("end_time").toLocalDateTime(),
                        rs.getString("status")),
                java.sql.Date.valueOf(tradeDate), shardCount, shardNo);
    }

    /** 批量加载子单并按母单分组 */
    public Map<Long, List<ChildOrderRow>> loadChildren(List<Long> orderIds) {
        Map<Long, List<ChildOrderRow>> result = new HashMap<>();
        for (int from = 0; from < orderIds.size(); from += IN_CHUNK) {
            List<Long> chunk = orderIds.subList(from, Math.min(orderIds.size(), from + IN_CHUNK));
            String placeholders = chunk.stream().map(id -> "?").collect(Collectors.joining(","));
            List<ChildOrderRow> rows = jdbcTemplate.query(
                    "SELECT child_id, order_id, side, exec_time, qty, price, filled_qty, avg_fill_price, "
                            + "is_cancelled, is_rejected "
                            + "FROM child_order WHERE order_id IN (" + placeholders + ") ORDER BY order_id, exec_time",
                    (rs, i) -> new ChildOrderRow(rs.getLong("child_id"), rs.getLong("order_id"),
                            rs.getString("side"), rs.getTimestamp("exec_time").toLocalDateTime(),
                            rs.getLong("qty"), rs.getBigDecimal("price"), rs.getLong("filled_qty"),
                            rs.getBigDecimal("avg_fill_price"), rs.getInt("is_cancelled") == 1,
                            rs.getInt("is_rejected") == 1),
                    chunk.toArray());
            for (ChildOrderRow row : rows) {
                result.computeIfAbsent(row.orderId(), k -> new ArrayList<>()).add(row);
            }
        }
        return result;
    }
}
