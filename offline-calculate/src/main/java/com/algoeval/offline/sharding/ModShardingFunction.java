package com.algoeval.offline.sharding;

import org.springframework.stereotype.Component;

/** MOD 取模分片（demo 默认；SQL 侧对应 MOD(order_id, N)） */
@Component
public class ModShardingFunction implements ShardingFunction {

    @Override
    public int shard(long orderId, int shardCount) {
        return (int) Math.floorMod(orderId, shardCount);
    }
}
