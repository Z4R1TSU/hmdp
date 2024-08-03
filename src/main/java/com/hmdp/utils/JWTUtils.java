package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JWTUtils {

    public String generateToken(UserDTO userDTO) {
//        设置当前时间和过期时间
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + RedisConstants.LOGIN_USER_TTL * 100);
//        设置JWT的payload
        Map<String, Object> userDTOMap = BeanUtil.beanToMap(userDTO);
        userDTOMap.put("id", userDTO.getId().toString());
        Map<String, Object> claims = new HashMap<>(userDTOMap);
//        生成一个JWT
        JwtBuilder jwtBuilder = Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expireDate);
//                .setClaims(claims)
//                .setIssuedAt(now)
//                .setExpiration(expireDate)
//                .signWith(SignatureAlgorithm.HS256, "ZariTsu");
        return jwtBuilder.compact();
    }

//    public String parseToken(String token) {
//        return Jwts.parser()
//                .require(token, new UserDTO())
//                .toString();
//    }

}
