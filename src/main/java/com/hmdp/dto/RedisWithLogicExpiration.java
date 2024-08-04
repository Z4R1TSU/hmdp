package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisWithLogicExpiration {

    private Object rawRedisData;

    private LocalDateTime expirationTime;

}
