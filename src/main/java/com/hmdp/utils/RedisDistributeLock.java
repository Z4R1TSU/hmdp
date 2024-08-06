package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.LockInfo;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

@Component
public class RedisDistributeLock implements Lock {

    private String lockName;

    private StringRedisTemplate stringRedisTemplate;

    public RedisDistributeLock(String lockName, StringRedisTemplate stringRedisTemplate) {
        this.lockName = lockName;
        this.stringRedisTemplate = stringRedisTemplate;
    }

//    @Override
//    public boolean tryLock(Long ttl) {
//        // 超时时间TTL，单位就默认为秒
//        String threadId = Thread.currentThread().getName();
//        Boolean result = stringRedisTemplate.opsForValue()
//                .setIfAbsent(RedisConstants.DISTRIBUTE_LOCK_KEY + lockName, threadId, ttl, TimeUnit.SECONDS);
//        if (result == null) {
//            return false;
//        }
//        return result;
//    }

    @Override
    public void lock() {

    }

    @Override
    public void lockInterruptibly() throws InterruptedException {

    }

    @Override
    public boolean tryLock() {
        return false;
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        // 超时时间TTL，单位就默认为秒
        String threadId = Thread.currentThread().getName();
        Boolean result = stringRedisTemplate.opsForValue()
                .setIfAbsent(RedisConstants.DISTRIBUTE_LOCK_KEY + lockName, threadId, time, unit);
        if (result == null) {
            return false;
        }
        return result;
    }

    @Override
    public void unlock() {
        String lockThreadId = stringRedisTemplate.opsForValue().get(RedisConstants.DISTRIBUTE_LOCK_KEY + lockName);
        String currentThreadId = Thread.currentThread().getName();
        if (lockThreadId == null || !lockThreadId.equals(currentThreadId)) {
            return;
        }
        stringRedisTemplate.delete(RedisConstants.DISTRIBUTE_LOCK_KEY + lockName);
    }

    @Override
    public Condition newCondition() {
        return null;
    }
}
