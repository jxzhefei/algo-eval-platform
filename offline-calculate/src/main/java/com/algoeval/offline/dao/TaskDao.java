package com.algoeval.offline.dao;

import com.algoeval.offline.domain.ComputeTaskRow;
import com.algoeval.source.sdk.SqlDialect;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 任务表 DAO：无中心抢占式调度的唯一协调者。
 * 所有协调复杂度压进乐观锁 SQL——没有 leader，就没有脑裂。
 */
@Repository
public class TaskDao {

    private static final String[] COLUMNS = {"trade_date", "shard_no", "status", "owner",
            "version", "heartbeat_time", "retry_count", "update_time"};

    private final JdbcTemplate jdbcTemplate;
    private final SqlDialect dialect;

    public TaskDao(JdbcTemplate jdbcTemplate, SqlDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    /** 幂等初始化：主键冲突即忽略（谁先到谁建，多节点同时初始化无害） */
    public boolean insertIgnore(LocalDate tradeDate, int shardNo) {
        try {
            jdbcTemplate.update(dialect.insert("compute_task", COLUMNS),
                    java.sql.Date.valueOf(tradeDate), shardNo, ComputeTaskRow.PENDING, null,
                    0L, null, 0, Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException e) {
            return false; // 已存在——别的节点或自己已建过
        }
    }

    /** 查看一个待抢占分片（只看，不锁） */
    public Optional<ComputeTaskRow> findPending(LocalDate tradeDate) {
        List<ComputeTaskRow> rows = jdbcTemplate.query(
                "SELECT trade_date, shard_no, status, owner, version, heartbeat_time, retry_count "
                        + "FROM compute_task WHERE trade_date=? AND status='PENDING' ORDER BY shard_no LIMIT 1",
                (rs, i) -> map(rs), java.sql.Date.valueOf(tradeDate));
        return rows.stream().findFirst();
    }

    /**
     * 乐观锁抢占：影响行数=1 → 抢到；=0 → 被别的节点抢先，零锁等待。
     */
    public boolean claim(LocalDate tradeDate, int shardNo, long expectedVersion, String owner) {
        int rows = jdbcTemplate.update(
                "UPDATE compute_task SET status='RUNNING', owner=?, version=version+1, "
                        + "heartbeat_time=?, update_time=? "
                        + "WHERE trade_date=? AND shard_no=? AND status='PENDING' AND version=?",
                owner, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                java.sql.Date.valueOf(tradeDate), shardNo, expectedVersion);
        return rows == 1;
    }

    /** 心跳续约（owner 条件防止节点复活后误更新已被回收的任务） */
    public void renewHeartbeat(LocalDate tradeDate, int shardNo, String owner) {
        jdbcTemplate.update(
                "UPDATE compute_task SET heartbeat_time=? WHERE trade_date=? AND shard_no=? AND owner=? AND status='RUNNING'",
                Timestamp.from(Instant.now()), java.sql.Date.valueOf(tradeDate), shardNo, owner);
    }

    public void markSuccess(LocalDate tradeDate, int shardNo, String owner) {
        jdbcTemplate.update(
                "UPDATE compute_task SET status='SUCCESS', update_time=? WHERE trade_date=? AND shard_no=? AND owner=?",
                Timestamp.from(Instant.now()), java.sql.Date.valueOf(tradeDate), shardNo, owner);
    }

    public void markFailed(LocalDate tradeDate, int shardNo, String owner) {
        jdbcTemplate.update(
                "UPDATE compute_task SET status='FAILED', update_time=? WHERE trade_date=? AND shard_no=? AND owner=?",
                Timestamp.from(Instant.now()), java.sql.Date.valueOf(tradeDate), shardNo, owner);
    }

    /**
     * 超时回收（每个节点都是回收者）：回收本身也是乐观锁语义——
     * 只有超时任务能被改，多节点同时回收同一行只有一个影响行数=1，其余无害失败。
     */
    public int reclaimStale(LocalDate tradeDate, long timeoutMs) {
        Timestamp threshold = Timestamp.from(Instant.now().minusMillis(timeoutMs));
        return jdbcTemplate.update(
                "UPDATE compute_task SET status='PENDING', owner=NULL, retry_count=retry_count+1, update_time=? "
                        + "WHERE trade_date=? AND status='RUNNING' AND heartbeat_time < ?",
                Timestamp.from(Instant.now()), java.sql.Date.valueOf(tradeDate), threshold);
    }

    /** 熔断：重试超限 → FAILED，避免毒丸任务无限循环 */
    public int circuitBreak(LocalDate tradeDate, int maxRetry) {
        return jdbcTemplate.update(
                "UPDATE compute_task SET status='FAILED', update_time=? "
                        + "WHERE trade_date=? AND status='PENDING' AND retry_count>=?",
                Timestamp.from(Instant.now()), java.sql.Date.valueOf(tradeDate), maxRetry);
    }

    public int countByStatus(LocalDate tradeDate, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM compute_task WHERE trade_date=? AND status=?",
                Integer.class, java.sql.Date.valueOf(tradeDate), status);
        return count == null ? 0 : count;
    }

    /** 当日是否全部完成（无 PENDING/RUNNING） */
    public boolean allFinished(LocalDate tradeDate) {
        return countByStatus(tradeDate, ComputeTaskRow.PENDING) == 0
                && countByStatus(tradeDate, ComputeTaskRow.RUNNING) == 0;
    }

    /** 各节点完成的分片数（展示 work-stealing 效果） */
    public List<OwnerStat> ownerStats(LocalDate tradeDate) {
        return jdbcTemplate.query(
                "SELECT owner, COUNT(*) AS cnt FROM compute_task WHERE trade_date=? AND status='SUCCESS' "
                        + "GROUP BY owner ORDER BY owner",
                (rs, i) -> new OwnerStat(rs.getString("owner"), rs.getInt("cnt")),
                java.sql.Date.valueOf(tradeDate));
    }

    public record OwnerStat(String owner, int shardCount) {
    }

    private static ComputeTaskRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp hb = rs.getTimestamp("heartbeat_time");
        return new ComputeTaskRow(rs.getDate("trade_date").toLocalDate(), rs.getInt("shard_no"),
                rs.getString("status"), rs.getString("owner"), rs.getLong("version"),
                hb == null ? null : hb.toLocalDateTime(), rs.getInt("retry_count"));
    }
}
