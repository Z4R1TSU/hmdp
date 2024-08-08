package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

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

    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            save(follow);
        } else {
            LambdaUpdateWrapper<Follow> lambdaUpdateWrapper = new LambdaUpdateWrapper<Follow>()
                    .eq(Follow::getFollowUserId, id)
                    .eq(Follow::getUserId, userId);
            boolean isDisFollow = remove(lambdaUpdateWrapper);
            if (!isDisFollow) {
                return Result.fail("无法取消关注");
            }
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

}
