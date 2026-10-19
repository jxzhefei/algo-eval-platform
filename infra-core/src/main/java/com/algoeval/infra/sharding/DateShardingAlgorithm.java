package com.algoeval.infra.sharding;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Properties;

/**
 * 按交易日分表算法（对齐真实系统 DateShardingAlgorithm）：
 * 路由键 trade_date → 物理表 {逻辑表}_{yyyyMMdd}。
 * 分片规则是纯函数——任何节点对同一交易日算出同一物理表。
 * 跨日范围查询路由到窗口内全部物理表（由离线预聚合兜底，避免实时跨表大查询）。
 */
public final class DateShardingAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public String doSharding(Collection<String> availableTargetNames,
                             PreciseShardingValue<Comparable<?>> shardingValue) {
        String suffix = suffixOf(shardingValue.getValue());
        for (String target : availableTargetNames) {
            if (target.endsWith("_" + suffix)) {
                return target;
            }
        }
        // 目标物理表不存在（超出预建窗口）：返回 null 由 ShardingSphere 报错暴露，
        // 生产部署由盘前预建表任务保证窗口覆盖
        return null;
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         RangeShardingValue<Comparable<?>> shardingValue) {
        return availableTargetNames;
    }

    private static String suffixOf(Object value) {
        LocalDate date = switch (value) {
            case LocalDate d -> d;
            case LocalDateTime dt -> dt.toLocalDate();
            case java.sql.Date d -> d.toLocalDate();
            case Timestamp ts -> ts.toLocalDateTime().toLocalDate();
            case String s -> LocalDate.parse(s);
            default -> throw new IllegalArgumentException("非法分片值类型: " + value.getClass());
        };
        return date.format(SUFFIX);
    }

    @Override
    public void init(Properties props) {
    }

    @Override
    public String getType() {
        return "DATE_SUFFIX";
    }
}
