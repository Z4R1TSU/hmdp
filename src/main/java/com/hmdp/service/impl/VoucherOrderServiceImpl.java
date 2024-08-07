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
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

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

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    private static final ExecutorService SECKILL_EXECUTOR = Executors.newSingleThreadExecutor();

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        SECKILL_EXECUTOR.submit(() -> {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("订单异常", e);
                }
            }
        });
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        RLock lock = redissonClient.getLock(RedisConstants.DISTRIBUTE_LOCK_KEY + voucherOrder.getId());
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("下单失败");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        Long result = null;
        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    UserHolder.getUser().getId().toString()
            );
        } catch (Exception e) {
            log.error("Lua脚本执行失败");
            throw new RuntimeException(e);
        }
        if (result != null && !result.equals(0L)) {
            // result为1表示库存不足，result为2表示用户已下单
            int r = result.intValue();
            return Result.fail(r == 2 ? "不能重复下单" : "库存不足");
        }
        // result = 0 证明用户可以进行抢购
        Long orderId = redisIdWorker.nextId();
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        this.proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok();
    }

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 1、判断当前用户是否超过购买两单的限制
        long countLong = this.count(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, userId));
        int count = (int) countLong;
        if (count >= 2) {
            // 当前用户超过限制
            log.error("当前用户超过购买限制");
            return;
        }
        // 2、用户是第一单，可以下单，秒杀券库存数量减一
        boolean flag = seckillVoucherService.update(new LambdaUpdateWrapper<SeckillVoucher>()
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .setDecrBy(SeckillVoucher::getStock, 1));
        if (!flag) {
            throw new RuntimeException("秒杀券扣减失败");
        }
        // 3、将订单保存到数据库
        flag = save(voucherOrder);
        if (!flag) {
            throw new RuntimeException("创建秒杀券订单失败");
        }
    }

//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        // 查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 不存在优惠券
//        if (seckillVoucher == null) {
//            return Result.fail("此优惠券不存在");
//        }
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        // 秒杀未开始，秒杀已结束，返回异常
//        if (beginTime.isAfter(LocalDateTime.now()) || endTime.isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀时间不匹配");
//        }
//        // 已经被抢完，返回异常
//        if (seckillVoucher.getStock() <= 0) {
//            return Result.fail("优惠券已抢完");
//        }
//        // 成功抢到，递减数量
//        // 一人最多只能抢两张秒杀优惠券，悲观锁只锁同一用户，其他用户不管(读用悲观锁)
////        synchronized (UserHolder.getUser().getId().toString().intern()) {
//        String userId = UserHolder.getUser().getId().toString();
////        RedisDistributeLock lock = new RedisDistributeLock(RedisConstants.SECKILL_STOCK_KEY + userId, stringRedisTemplate);
////        boolean isLock = lock.tryLock(60L, TimeUnit.SECONDS);
//        RLock lock = redissonClient.getLock(RedisConstants.SECKILL_USER_KEY + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("你无法再抢购更多优惠券");
//        }
//        try {
//            LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<VoucherOrder>()
//                    .eq(VoucherOrder::getVoucherId, voucherId)
//                    .eq(VoucherOrder::getUserId, UserHolder.getUser().getId());
//            long count = count(queryWrapper);
//            if (count >= 2) {
//                return Result.fail("你无法再抢购更多优惠券");
//            }
//            // 这里要注意，即使使用了setDecrBy的方法还是需要CAS来判断竞争，如果stock数量不对，说明出现了竞态(写用乐观锁)
//            LambdaUpdateWrapper<SeckillVoucher> updateWrapper = new LambdaUpdateWrapper<SeckillVoucher>()
//                    .eq(SeckillVoucher::getVoucherId, voucherId)
//                    .eq(SeckillVoucher::getStock, seckillVoucher.getStock())
//                    .gt(SeckillVoucher::getStock, 0)
//                    .setDecrBy(SeckillVoucher::getStock, 1);
//            boolean isDecr = seckillVoucherService.update(updateWrapper);
//            if (!isDecr) {
//                return Result.fail("抢购失败");
//            }
//            // 生成订单，并返回订单号
//            VoucherOrder voucherOrder = new VoucherOrder();
//            Long orderId = redisIdWorker.nextId();
//            voucherOrder.setId(orderId);
//            voucherOrder.setUserId(UserHolder.getUser().getId());
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//            return Result.ok(orderId);
//        } finally {
//            lock.unlock();
//        }
////        }
//    }

}
