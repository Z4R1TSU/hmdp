package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.RedisWithLogicalExpiration;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

@Slf4j
@Component
public class CacheUtils {

    private final StringRedisTemplate stringRedisTemplate;

    // 线程池，用来对新的线程进行更新缓存操作
    private static final ExecutorService CACHE_UPDATER = Executors.newFixedThreadPool(10);

    // 用来调取Redisson的官方锁，提供更安全、高效的服务
    RedissonClient redissonClient = Redisson.create();

    // 异步地管理锁的阻塞，当其他线程阻塞锁的申请时，不再进行申请，而是等到释放锁时通知
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();

    public CacheUtils(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setLogicalExpiration(String key, Object value, Long time, TimeUnit timeUnit) {
        LocalDateTime timePeriod = LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time));
        RedisWithLogicalExpiration valueWithLogicalExpiration = new RedisWithLogicalExpiration(value, timePeriod);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(valueWithLogicalExpiration));
    }

    public <R, T> R queryByIdMutex(T id, Class<R> type, Function<T, R> queryFunction) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // json不为null且为blank，说明它一定为空字符串，说明它有被穿透的风险，所以直接报错
            return null;
        }
        // 申请到了则进行数据库的查询和redis的更新
        R result = queryFunction.apply(id);
        if (result == null) {
            // 防止缓存穿透，将数据库值为null的key以空值value记录在redis中
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(result), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return result;
    }

    public <R, T> R queryByIdWithLogicalExpiration(T id, Class<R> type, Function<T, R> queryFunction) throws InterruptedException {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 缓存中查询是否存在店铺信息
        String json = stringRedisTemplate.opsForValue().get(key);
        // 找不到则直接返回空，因为热点信息我们不能让它访问数据库，这样会导致雪崩
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 找到就需要判断逻辑时间是否过期
        RedisWithLogicalExpiration redisData = JSONUtil.toBean(json, RedisWithLogicalExpiration.class);
        R result = JSONUtil.toBean((JSONObject) redisData.getRawRedisData(), type);
        LocalDateTime expirationTime = redisData.getExpirationTime();
        if (expirationTime == null) {
            return null;
        }
        // 没过期说明很新，不存在缓存一致性问题，直接返回
        if (expirationTime.isAfter(LocalDateTime.now())) {
            return result;
        }
        // 申请锁，避免多个线程同时对缓存修改产生的访问数据库导致雪崩
        boolean requireLock = requireLock(id.toString());
        // 获取锁失败，则返回过期店铺信息
        if (!requireLock) {
            return result;
        }
        // 获取锁成功，开启另一个独立的线程用于更新数据库信息到缓存
        CACHE_UPDATER.submit(() -> {
            try {
                R queryResult = queryFunction.apply(id);
                if (queryResult == null) {
                    log.debug("找不到指定店铺");
                }
                // 封装店铺
                LocalDateTime expireTime = LocalDateTime.now().plusSeconds(RedisConstants.CACHE_SHOP_TTL);
                RedisWithLogicalExpiration redisWithLogicalExpiration = new RedisWithLogicalExpiration(queryResult, expireTime);
                // 将带有逻辑过期时间的店铺加到redis缓存当中(不设置实际过期时间)
                stringRedisTemplate.opsForValue()
                        .set(RedisConstants.CACHE_SHOP_KEY + id.toString(), JSONUtil.toJsonStr(redisWithLogicalExpiration));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                releaseLock(id.toString());
            }
        });
        // 释放锁
        releaseLock(id.toString());
        // 过期了需要替换，但这个线程还是直接返回旧的信息
        return result;
    }

    private boolean requireLock(String key) throws InterruptedException {
        RLock lock = redissonClient.getLock(key);
        return lock.tryLock(100, TimeUnit.MILLISECONDS);
    }

    private void releaseLock(String key) {
        RLock lock = redissonClient.getLock(key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

}
