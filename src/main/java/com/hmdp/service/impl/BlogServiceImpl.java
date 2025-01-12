package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.BlogResult;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::fillBlogWithUserInfo);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("找不到指定博客");
        }
        // 查询用户信息，因为我们需要显示是谁发布了这篇博客
        fillBlogWithUserInfo(blog);
        return Result.ok(blog);
    }

    /**
     *
     * @param id 博客的ID
     * @return 如果未点赞则点赞，如果已点赞则取消点赞
     */
    @Override
    public Result likeBlog(Long id) {
        // 先找找在Redis的点赞情况
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        String userId = UserHolder.getUser().getId().toString();
        Boolean isLike = stringRedisTemplate.opsForSet().isMember(key, userId);
        if (isLike != null && isLike) {
            // 该用户已经给这个博客点过赞了，取消点赞
            LambdaUpdateWrapper<Blog> blogLambdaUpdateWrapper = new LambdaUpdateWrapper<Blog>()
                    .eq(Blog::getId, id)
                    .setDecrBy(Blog::getLiked, 1);
            boolean isUpdate = update(blogLambdaUpdateWrapper);
            if (isUpdate) {
                stringRedisTemplate.opsForSet().remove(key, userId);
            }
            return Result.ok();
        }
        // 该用户没有给这个博客点过赞或者之前取消了点赞，则点赞
        LambdaUpdateWrapper<Blog> blogLambdaUpdateWrapper = new LambdaUpdateWrapper<Blog>()
                .eq(Blog::getId, id)
                .setIncrBy(Blog::getLiked, 1);
        boolean isUpdate = update(blogLambdaUpdateWrapper);
        if (isUpdate) {
            stringRedisTemplate.opsForSet().add(key, userId);
        }
        return Result.ok();
    }

    @Override
    public Result likeBlogWithScore(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        String userId = UserHolder.getUser().getId().toString();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);
        if (score != null) {
            LambdaUpdateWrapper<Blog> blogLambdaUpdateWrapper = new LambdaUpdateWrapper<Blog>()
                    .eq(Blog::getId, id)
                    .setDecrBy(Blog::getLiked, 1);
            boolean isUpdate = update(blogLambdaUpdateWrapper);
            if (isUpdate) {
                stringRedisTemplate.opsForZSet().remove(key, userId);
            }
        } else {
            LambdaUpdateWrapper<Blog> blogLambdaUpdateWrapper = new LambdaUpdateWrapper<Blog>()
                    .eq(Blog::getId, id)
                    .setIncrBy(Blog::getLiked, 1);
            boolean isUpdate = update(blogLambdaUpdateWrapper);
            if (isUpdate) {
                stringRedisTemplate.opsForZSet().add(key, userId, System.currentTimeMillis());
            }
        }
        Set<String> top3Liked = stringRedisTemplate.opsForZSet().range(key, 0, 3);
        if (top3Liked == null || top3Liked.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = top3Liked.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(userIds)
                .stream().
                map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        boolean isSaveSucceed = save(blog);
        if (!isSaveSucceed) {
            return Result.fail("笔记保存失败");
        }
        List<Follow> followers = followService.list(
                new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, userId));
        followers.stream()
                .map(Follow::getUserId)
                .forEach(id -> {
                    String key = RedisConstants.FEED_KEY + id;
                    stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
                });
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryFollowed(Long lastId, Integer offset) {
        // 查询本人信息及id
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FEED_KEY + userId;
        // 在redis中查询是否有自己关注的人发布的博客
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析博客信息
        List<Long> ids = typedTuples.stream()
                .map(ZSetOperations.TypedTuple::getValue).filter(Objects::nonNull)
                .map(Long::valueOf)
                .collect(Collectors.toList());
        AtomicLong minTime = new AtomicLong(0);
        AtomicInteger ofset = new AtomicInteger(1);
        typedTuples.stream()
                .forEach(tuple -> {
                    long currentTime = tuple.getScore().longValue();
                    if (currentTime == minTime.get()) {
                        ofset.incrementAndGet();
                    } else {
                        minTime.set(currentTime);
                        ofset.set(1);
                    }
                });
        // 从数据库中找出，所有符合信息的博客创造者
        List<Blog> blogs = list(new LambdaQueryWrapper<Blog>().in(Blog::getId, ids));
        // 封装成BlogResult这个DTO
        BlogResult blogResult = new BlogResult();
        blogResult.setList(blogs);
        blogResult.setMinTime(minTime.get());
        blogResult.setOffset(ofset.get());
        // 返回所有本人关注的发布的博客信息
        return Result.ok(blogResult);
    }

    private void fillBlogWithUserInfo(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
