package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private IUserService userService;

    private final StringRedisTemplate stringRedisTemplate;

    public FollowServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FOLLOW_KEY + userId.toString();
        if (isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean isFollowSucceed = save(follow);
            if (isFollowSucceed) {
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        } else {
            LambdaUpdateWrapper<Follow> lambdaUpdateWrapper = new LambdaUpdateWrapper<Follow>()
                    .eq(Follow::getFollowUserId, id)
                    .eq(Follow::getUserId, userId);
            boolean isDisFollow = remove(lambdaUpdateWrapper);
            if (!isDisFollow) {
                return Result.fail("无法取消关注");
            }
            stringRedisTemplate.opsForSet().remove(key, id.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        LambdaQueryWrapper<Follow> lambdaQueryWrapper = new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, id)
                .eq(Follow::getUserId, UserHolder.getUser().getId());
        Follow follow = getOne(lambdaQueryWrapper);
        return Result.ok(follow);
    }

    @Override
    public Result findLikeCommon(Long id) {
        Long ownId = UserHolder.getUser().getId();
        String ownKey = RedisConstants.FOLLOW_KEY + ownId.toString();
        String otherKey = RedisConstants.FOLLOW_KEY + id.toString();
        Set<String> likeInCommon = stringRedisTemplate.opsForSet().intersect(ownKey, otherKey);
        if (likeInCommon == null || likeInCommon.isEmpty()) {
            List<Follow> ownInfo = list(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, ownId));
            List<Follow> otherInfo = list(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, id));
            Set<Long> ownFollowed = ownInfo.stream().map(Follow::getFollowUserId).collect(Collectors.toSet());
            Set<Long> otherFollowed = otherInfo.stream().map(Follow::getFollowUserId).collect(Collectors.toSet());
            ownFollowed.retainAll(otherFollowed);
            List<Long> ids = new ArrayList<>(ownFollowed);
            List<UserDTO> userDTOList = userService.listByIds(ids)
                    .stream()
                    .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                    .collect(Collectors.toList());
            return Result.ok(userDTOList);
        }
        List<Long> ids = likeInCommon.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

}
