package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.JWTUtils;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JWTUtils jwtUtils;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);
//        session.setAttribute("code", code);
        stringRedisTemplate.opsForValue()
                .set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送验证码成功，验证码为 {}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        校对验证码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String code = loginForm.getCode();
//        Object sessionCode = session.getAttribute("   code");
        String redisCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (redisCode == null || code.compareTo(redisCode) != 0) {
            return Result.fail("验证码错误");
        }
//        找出目标用户，并转化为去私密信息的userDTO形式
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            user = createUserByPhone(phone);
        }
//        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = new UserDTO(user.getId(), user.getNickName(), user.getIcon());
//        session.setAttribute("user", userDTO);
        // 这里将toString设为true是因为redis的value只能是字符串，所以这里将userDTO转为json字符串存入redis
//        生成token并保存到redis
        String token = jwtUtils.generateToken(userDTO);
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<String, Object> userDTOMap = BeanUtil.beanToMap(userDTO);
//        // 将DTO转化成的map的id字段从long转到string，使其符合redis的string键值对规范
        userDTOMap.put("id", user.getId().toString());
        stringRedisTemplate.opsForHash().putAll(tokenKey, userDTOMap);
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        return Result.ok(token);
    }

    @Override
    public Result logout(HttpServletRequest request) {
//        UserDTO userDTO = UserHolder.getUser();
        String token = request.getHeader("authorization");
//        将UserHolder记录的在线登录状态的用户去掉
        UserHolder.removeUser();
//        再去掉redis当中存储的信息
        stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        return Result.ok();
    }

    @Override
    public User createUserByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_" + RandomUtil.randomString(10));
        userMapper.insert(user);
        return user;
    }

}
