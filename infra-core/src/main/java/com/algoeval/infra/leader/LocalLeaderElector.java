package com.algoeval.infra.leader;

import org.springframework.stereotype.Component;

/**
 * 单节点选主实现：恒为主节点（最小组件集部署形态）。
 * fullmw 构建下由 @Profile("redis") 的 RedissonLeaderElector（@Primary）替代。
 */
@Component
public class LocalLeaderElector implements LeaderElector {

    @Override
    public boolean isLeader() {
        return true;
    }
}
