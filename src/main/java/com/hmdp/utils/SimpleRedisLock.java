package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private RedisTemplate redisTemplate;
    private static final String key_prefix="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    public SimpleRedisLock(String name, RedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String id =ID_PREFIX+ Thread.currentThread().getId();
        // 获取锁
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key_prefix + name, id + "",
                timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 获取线程标识
        String threadId=ID_PREFIX+ Thread.currentThread().getId();
        String Id = (String) redisTemplate.opsForValue().get(key_prefix + name);
        if (threadId.equals(Id)){
            // 释放锁
            redisTemplate.delete(key_prefix+name);
        }

    }
}
