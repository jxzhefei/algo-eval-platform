package com.algoeval.offline.sharding;

/**
 * 分片函数：分片规则是纯函数——任何节点对同一母单算出同样归属。
 * demo 用 MOD(order_id, N)；生产可换一致性哈希（扩容时仅 1/N 母单迁移归属）。
 */
public interface ShardingFunction {

    int shard(long orderId, int shardCount);
}
