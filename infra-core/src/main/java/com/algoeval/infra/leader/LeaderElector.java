package com.algoeval.infra.leader;

/**
 * 采集选主契约（对齐设计文档 §4.4 / §13.6）：
 * 多节点部署时仅主节点对外分发行情，防止重复发布。
 * 默认实现恒为主（单节点）；fullmw 构建下由 RedissonLeaderElector
 * （看门狗续期分布式锁）替代——演进决策：etcd 退役，选主收敛 Redis。
 */
public interface LeaderElector {

    boolean isLeader();
}
