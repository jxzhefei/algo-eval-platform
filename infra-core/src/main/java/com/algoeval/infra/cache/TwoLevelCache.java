package com.algoeval.infra.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 二级缓存（对齐设计文档 §6.6）：Caffeine 本地（毫秒级，扛画像页"一次渲染几十张图"）
 * + Redis 共享层（多实例一致，TTL 更长）。L2 关闭或不可用时自动降级为 L1。
 */
@Component
public class TwoLevelCache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    private final Cache<String, Object> l1;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean redisEnabled;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    public TwoLevelCache(ObjectProvider<StringRedisTemplate> redisProvider,
                         @Value("${algo.cache.redis.enabled:false}") boolean redisEnabled,
                         @Value("${algo.cache.l1-ttl-seconds:60}") long l1TtlSeconds,
                         @Value("${algo.cache.l2-ttl-seconds:300}") long l2TtlSeconds) {
        this.redisProvider = redisProvider;
        this.redisEnabled = redisEnabled;
        this.l1 = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(l1TtlSeconds))
                .maximumSize(10_000)
                .build();
        this.l2TtlSeconds = l2TtlSeconds;
    }

    private final long l2TtlSeconds;

    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        Object cached = l1.getIfPresent(key);
        if (cached != null) {
            return type.cast(cached);
        }
        T value = loadFromL2(key, type);
        if (value == null) {
            value = loader.get();
            if (value == null) {
                return null;
            }
            putToL2(key, value);
        }
        l1.put(key, value);
        return value;
    }

    public void evict(String prefix) {
        l1.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        if (redisEnabled) {
            try {
                StringRedisTemplate redis = redisProvider.getIfAvailable();
                if (redis != null) {
                    redis.keys(prefix + "*").forEach(redis::delete);
                }
            } catch (Exception e) {
                log.warn("[cache] L2 失效失败（降级忽略）: {}", e.getMessage());
            }
        }
    }

    private <T> T loadFromL2(String key, Class<T> type) {
        if (!redisEnabled) {
            return null;
        }
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return null;
            }
            String json = redis.opsForValue().get(key);
            return json == null ? null : mapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[cache] L2 读取失败（降级 L1/直查）: {}", e.getMessage());
            return null;
        }
    }

    private void putToL2(String key, Object value) {
        if (!redisEnabled) {
            return;
        }
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.opsForValue().set(key, mapper.writeValueAsString(value),
                        Duration.ofSeconds(l2TtlSeconds));
            }
        } catch (Exception e) {
            log.warn("[cache] L2 写入失败（降级忽略）: {}", e.getMessage());
        }
    }
}
