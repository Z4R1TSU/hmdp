package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    public Result queryById(Long id) throws InterruptedException {
        Shop shop = queryByIdMutex(id);
        if (shop == null) {
            return Result.fail("找不到指定店铺");
        }
        return Result.ok(shop);
    }

    private Shop queryByIdMutex(Long id) throws InterruptedException {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            // shopJson不为null，说明它一定为空字符串，说明它有被穿透的风险，所以直接报错
            return null;
        }
        // 尝试申请锁
        Shop shop = null;
        try {
            boolean requireLock = requireLock(key);
            // 若申请不到，则睡眠后重新申请(这个很朴素性能很差，我不知道在Java怎么进行进程间的通信)
            if (!requireLock) {
                Thread.sleep(50);
                return queryByIdMutex(id);
            }
            // 申请到了则进行数据库的查询和redis的更新
            shop = getById(id);
            if (shop == null) {
                // 防止缓存穿透，将数据库值为null的key以空值value记录在redis中
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue()
                    .set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 不论怎么报错都要解锁
            releaseLock(key);
        }
        return shop;
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

    private boolean requireLock(String key) {
        Boolean requireResult = stringRedisTemplate.opsForValue()
                .setIfAbsent(RedisConstants.LOCK_SHOP_KEY + key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        if (requireResult == null) {
            return false;
        }
        return requireResult;
    }

    private void releaseLock(String key) {
        stringRedisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + key);
    }

}
