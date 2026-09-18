package com.yuntu.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存服务
 *
 * 封装缓存的读取/写入，带优雅降级：Redis 不可用时直接跳过，
 * 不影响主流程（对应 Python 版的 cache_service.py）。
 *
 * Key 设计：业务前缀 + 参数拼串，统一加 trip_planner: 前缀。
 *   地图:  trip_planner:map:place:{关键词}:{城市}:{page_size}
 *   天气:  trip_planner:weather:forecast:{城市}:{日期范围}
 * 过期策略：写缓存时指定 TTL，到期自动过期重新拉取。
 */
@Slf4j
@Service
public class CacheService {

    private static final String KEY_PREFIX = "trip_planner:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean cacheEnabled;

    public CacheService(RedisTemplate<String, Object> redisTemplate,
                        ObjectMapper objectMapper,
                        @Value("${redis.enabled:true}") boolean cacheEnabled) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheEnabled = cacheEnabled;
    }

    private String buildKey(String key) {
        return KEY_PREFIX + key;
    }

    /**
     * 读取缓存；未命中或 Redis 不可用时返回 null。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        if (!cacheEnabled) {
            return null;
        }
        try {
            Object value = redisTemplate.opsForValue().get(buildKey(key));
            if (value == null) {
                return null;
            }
            if (type.isInstance(value)) {
                return (T) value;
            }
            // 类型不匹配时通过 Jackson 转换（Map/List -> 具体对象）
            return objectMapper.convertValue(value, type);
        } catch (Exception e) {
            // Redis 不可用时优雅降级，不影响主流程
            log.warn("读取缓存失败（降级，已回源）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入缓存；Redis 不可用时直接跳过。
     *
     * @param key           缓存 key（不含前缀）
     * @param value         缓存值
     * @param expireSeconds TTL，单位秒
     */
    public void set(String key, Object value, long expireSeconds) {
        if (!cacheEnabled) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(buildKey(key), value, expireSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入缓存失败（降级，已跳过）: {}", e.getMessage());
        }
    }

    /**
     * 按 key 前缀删除缓存（如发布新攻略版本后失效该城市 RAG 查询缓存）。
     * Redis 不可用时静默跳过。前缀含内部命名空间前缀（如 "rag:guide:"）。
     */
    public void deleteByPrefix(String keyPrefix) {
        if (!cacheEnabled || keyPrefix == null || keyPrefix.isBlank()) {
            return;
        }
        try {
            Set<String> keys = redisTemplate.keys(buildKey(keyPrefix) + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("缓存按前缀清除: {} 共 {} 个 key", keyPrefix, keys.size());
            }
        } catch (Exception e) {
            log.warn("按前缀清除缓存失败（降级，已跳过）: {}", e.getMessage());
        }
    }
}
