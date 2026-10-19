package com.algoeval.infra.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端装配：选主（§13.6，etcd 退役后的收敛方案）与汇总分布式锁（§13.4）共用。
 * 真实中间件轨道默认启用（algo.redis.enabled=true）；离线计算单测等无 Redis 上下文
 * 显式置 false，锁/选主自动降级本地实现。
 */
@Configuration
@ConditionalOnProperty(name = "algo.redis.enabled", havingValue = "true")
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password) {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        if (password == null || password.isBlank()) {
            config.useSingleServer().setAddress(address).setConnectionPoolSize(32);
        } else {
            config.useSingleServer().setAddress(address).setPassword(password).setConnectionPoolSize(32);
        }
        log.info("[infra] Redisson 客户端就绪: {}", address);
        return Redisson.create(config);
    }
}
