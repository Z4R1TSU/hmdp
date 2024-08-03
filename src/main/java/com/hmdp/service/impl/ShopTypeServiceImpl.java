package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private RedisTemplate<String, ShopType> redisTemplate;

    @Override
    public Result queryTypeList() {
        String key = RedisConstants.CACHE_SHOP_KEY;
        List<ShopType> shopTypeCacheList = redisTemplate.opsForList().range(key, 0, -1);
        if (shopTypeCacheList != null && !shopTypeCacheList.isEmpty()) {
            return Result.ok(shopTypeCacheList);
        }
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList == null || shopTypeList.isEmpty()) {
            return Result.fail("没有店铺种类的记录");
        }
        // 这里不使用leftPush是因为这样容易导致缓存穿透
        redisTemplate.opsForList().rightPushAll(key, shopTypeList);
        redisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shopTypeList);
    }

}
