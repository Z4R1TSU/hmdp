package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.RedisWithLogicalExpiration;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheUtils;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheUtils cacheUtils;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ShopMapper shopMapper;

    @Override
    public Result queryById(Long id) throws InterruptedException {
//        Shop shop = queryByIdMutex(id);
//        Shop shop = cacheUtils.queryByIdMutex(id, Shop.class, this::getById);
        Shop shop = cacheUtils.queryByIdMutex(id, Shop.class, this::getById);
        if (shop == null) {
            return Result.fail("找不到指定店铺");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)   // 事务注解: 保证数据一致性和安全性，在异常时自动回滚，写操作一般都可以加
    public Result updateShop(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺ID不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

//    private Shop queryByIdMutex(Long id) throws InterruptedException {
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(shopJson)) {
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        if (shopJson != null) {
//            // shopJson不为null，说明它一定为空字符串，说明它有被穿透的风险，所以直接报错
//            return null;
//        }
//        // 尝试申请锁
//        Shop shop = null;
//        try {
//            boolean requireLock = requireLock(key);
//            // 若申请不到，则睡眠后重新申请(这个很朴素性能很差，我不知道在Java怎么进行进程间的通信)
//            if (!requireLock) {
//                Thread.sleep(50);
//                return queryByIdMutex(id);
//            }
//            // 申请到了则进行数据库的查询和redis的更新
//            shop = getById(id);
//            if (shop == null) {
//                // 防止缓存穿透，将数据库值为null的key以空值value记录在redis中
//                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            stringRedisTemplate.opsForValue()
//                    .set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 不论怎么报错都要解锁
//            releaseLock(key);
//        }
//        return shop;
//    }

//    private Shop queryByIdWithLogicalExpiration(Long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        // 缓存中查询是否存在店铺信息
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        // 找不到则直接返回空，因为热点信息我们不能让它访问数据库，这样会导致雪崩
//        if (StrUtil.isBlank(shopJson)) {
//            return null;
//        }
//        // 找到就需要判断逻辑时间是否过期
//        RedisWithLogicalExpiration redisData = JSONUtil.toBean(shopJson, RedisWithLogicalExpiration.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getRawRedisData(), Shop.class);
//        LocalDateTime expirationTime = redisData.getExpirationTime();
//        if (expirationTime == null) {
//            return null;
//        }
//        // 没过期说明很新，不存在缓存一致性问题，直接返回
//        if (expirationTime.isAfter(LocalDateTime.now())) {
//            return shop;
//        }
//        // 申请锁，避免多个线程同时对缓存修改产生的访问数据库导致雪崩
//        boolean requireLock = requireLock(id.toString());
//        // 获取锁失败，则返回过期店铺信息
//        if (!requireLock) {
//            return shop;
//        }
//        // 获取锁成功，开启另一个独立的线程用于更新数据库信息到缓存
//        CACHE_UPDATER.submit(() -> {
//            try {
//                setNonVolatileCache(id, RedisConstants.CACHE_SHOP_TTL);
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            } finally {
//                releaseLock(id.toString());
//            }
//        });
//        // 释放锁
//        releaseLock(id.toString());
//        // 过期了需要替换，但这个线程还是直接返回旧的信息
//        return shop;
//    }

//    private boolean requireLock(String key) {
//        Boolean requireResult = stringRedisTemplate.opsForValue()
//                .setIfAbsent(RedisConstants.LOCK_SHOP_KEY + key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
//        if (requireResult == null) {
//            return false;
//        }
//        return requireResult;
//    }
//
//    private void releaseLock(String key) {
//        stringRedisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + key);
//    }
//
//    private void setNonVolatileCache(Long id, Long ttl) {
//        // 根据店铺ID查询到店铺主体
//        Shop shop = getById(id);
//        if (shop == null) {
//            log.debug("找不到指定店铺");
//        }
//        // 封装店铺
//        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(ttl);
//        RedisWithLogicalExpiration redisWithLogicalExpiration = new RedisWithLogicalExpiration(shop, expireTime);
//        // 将带有逻辑过期时间的店铺加到redis缓存当中(不设置实际过期时间)
//        stringRedisTemplate.opsForValue()
//                .set(RedisConstants.CACHE_SHOP_KEY + id.toString(), JSONUtil.toJsonStr(redisWithLogicalExpiration));
//    }

}
