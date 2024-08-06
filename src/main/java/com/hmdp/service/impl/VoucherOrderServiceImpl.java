package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.RedisIdWorker;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisDistributeLock;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        // 查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 不存在优惠券
        if (seckillVoucher == null) {
            return Result.fail("此优惠券不存在");
        }
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        // 秒杀未开始，秒杀已结束，返回异常
        if (beginTime.isAfter(LocalDateTime.now()) || endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀时间不匹配");
        }
        // 已经被抢完，返回异常
        if (seckillVoucher.getStock() <= 0) {
            return Result.fail("优惠券已抢完");
        }
        // 成功抢到，递减数量
        // 一人最多只能抢两张秒杀优惠券，悲观锁只锁同一用户，其他用户不管(读用悲观锁)
//        synchronized (UserHolder.getUser().getId().toString().intern()) {
        String userId = UserHolder.getUser().getId().toString();
        RedisDistributeLock lock = new RedisDistributeLock(RedisConstants.SECKILL_STOCK_KEY + userId, stringRedisTemplate);
        boolean isLock = lock.tryLock(60L, TimeUnit.SECONDS);
        if (!isLock) {
            return Result.fail("你无法再抢购更多优惠券");
        }
        try {
            LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<VoucherOrder>()
                    .eq(VoucherOrder::getVoucherId, voucherId)
                    .eq(VoucherOrder::getUserId, UserHolder.getUser().getId());
            long count = count(queryWrapper);
            if (count >= 2) {
                return Result.fail("你无法再抢购更多优惠券");
            }
            // 这里要注意，即使使用了setDecrBy的方法还是需要CAS来判断竞争，如果stock数量不对，说明出现了竞态(写用乐观锁)
            LambdaUpdateWrapper<SeckillVoucher> updateWrapper = new LambdaUpdateWrapper<SeckillVoucher>()
                    .eq(SeckillVoucher::getVoucherId, voucherId)
                    .eq(SeckillVoucher::getStock, seckillVoucher.getStock())
                    .gt(SeckillVoucher::getStock, 0)
                    .setDecrBy(SeckillVoucher::getStock, 1);
            boolean isDecr = seckillVoucherService.update(updateWrapper);
            if (!isDecr) {
                return Result.fail("抢购失败");
            }
            // 生成订单，并返回订单号
            VoucherOrder voucherOrder = new VoucherOrder();
            Long orderId = redisIdWorker.nextId();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(UserHolder.getUser().getId());
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            return Result.ok(orderId);
        } finally {
            lock.unlock();
        }
//        }
    }

}
