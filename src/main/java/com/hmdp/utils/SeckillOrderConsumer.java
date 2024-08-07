package com.hmdp.utils;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@EnableKafka
@Slf4j
public class SeckillOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @KafkaListener(topics = RedisConstants.SECKILL_STOCK_KEY)
    public void consumeOrder(VoucherOrder voucherOrder) {
        RLock lock = redissonClient.getLock(RedisConstants.DISTRIBUTE_LOCK_KEY + voucherOrder.getId());
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("下单失败");
            return;
        }
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

}
