package com.splitbuddy.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis 降级容错封装：所有缓存/去重操作统一走这里。
 *
 * <p>解决什么问题：本项目 Redis 只承担"缓存加速 + 提醒去重"这类尽力而为的职责，
 * Redis 挂掉绝不能拖垮主业务（记账是 MySQL 事实数据，不依赖 Redis）。
 * 每个方法整体 try/catch 任何 Exception（连接超时、序列化、权限都算），
 * 失败只打 warn 日志并返回"未命中/未去重"的中性结果——调用方拿到降级值即可继续走库。</p>
 *
 * <p>取舍：不引入 SpringCache 抽象。@Cacheable 的异常传播语义要靠自写
 * CacheErrorHandler，绕一圈不如这层薄封装直白（单机 MVP 够用，面试可讲这层选型）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSafeService {

    private final StringRedisTemplate redis;

    /** 读缓存：异常返回 null（等同于未命中，调用方回落数据库） */
    public String get(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis 读降级 key={}：{}", key, e.getMessage());
            return null;
        }
    }

    /** 写缓存：带 TTL，异常静默降级（下次读自然未命中） */
    public void set(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis 写降级 key={}：{}", key, e.getMessage());
        }
    }

    /** 删缓存：异常静默——删失败最多让旧缓存多活到 TTL，可接受 */
    public void delete(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis 删降级 key={}：{}", key, e.getMessage());
        }
    }

    /**
     * setnx + TTL 去重标记：Redis 可用时返回是否"首次拿到"；
     * Redis 不可用时返回 true（降级策略：宁可重复提醒一条日志，也不静默丢提醒——
     * 提醒本身只是 log 输出，重复无害；丢提醒才是功能缺失）。
     */
    public boolean tryAcquireMark(String key, Duration ttl) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1", ttl);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("Redis setnx 降级 key={}：{}，按未去重处理", key, e.getMessage());
            return true;
        }
    }
}
