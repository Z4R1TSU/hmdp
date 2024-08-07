package com.hmdp.utils;

import com.hmdp.entity.VoucherOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class SeckillOrderProducer {

    @Resource
    private KafkaTemplate<String, VoucherOrder> kafkaTemplate;

    public void sendOrder(VoucherOrder voucherOrder) {
        kafkaTemplate.send(RedisConstants.SECKILL_STOCK_KEY, voucherOrder);
    }

}
