package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Component
public class CacheClient {
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Autowired
    private RedisTemplate redisTemplate;

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //写入redis
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(Long time, TimeUnit unit, String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback) {
        // 1从redis查询缓存
        String json = (String) redisTemplate.opsForValue().get(keyPrefix + id);
        // 2判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否为空值
        if (json != null) {
            // 返回错误信息
            return null;
        }
        // 4不存在，根据ID查询数据库
        R r = dbFallback.apply(id);
        // 5不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            redisTemplate.opsForValue().set(keyPrefix + id, "", 2, TimeUnit.MINUTES);
            return null;
        }
        // 6存在，将商铺数据写入Redis
        this.set(keyPrefix + id, r, time, unit);
        // 7返回
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time,
                                            TimeUnit unit) {
        // 1从redis查询缓存
        String json = (String) redisTemplate.opsForValue().get(keyPrefix + id);
        // 2判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3不存在，直接返回
            return null;
        }

        // 4命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1未过期，直接返回店铺信息
            return r;
        }
        // 5.2已经过期，需要进行缓存重建
        // 6缓存重建
        // 6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2判断是否获取成功
        if (isLock) {
            // 6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(keyPrefix + id, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }

            });
        }
        // 6.4失败，返回过期商铺信息
        return r;
    }


    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time,
                                    TimeUnit unit) {
        // 1从redis查询缓存
        String json = (String) redisTemplate.opsForValue().get(keyPrefix + id);
        // 2判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3存在，直接返回
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        // 判断命中的是否为空值
        if (json != null) {
            // 返回错误信息
            return null;
        }
        // 4实现缓存重建
        // 4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2判断是否获取成功
            if (!isLock) {
                // 4.3失败，则休眠并且重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }

            // 4.4成功，根据ID查询数据库
            r = dbFallback.apply(id);
            // 5不存在，返回错误
            if (r == null) {
                // 将空值写入redis
                redisTemplate.opsForValue().set(keyPrefix + id, "", 2, TimeUnit.MINUTES);
                return null;
            }
            // 6存在，将商铺数据写入Redis
            redisTemplate.opsForValue().set(keyPrefix + id, JSONUtil.toJsonStr(r), 30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7释放互斥锁
            unlock(lockKey);
        }

        // 8返回

        return r;
    }


    private boolean tryLock(String key) {
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        redisTemplate.delete(key);
    }
}
