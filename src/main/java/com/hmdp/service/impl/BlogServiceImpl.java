package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IFollowService followService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result queryBlogById(long id) {
        // 查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        //查询用户
        queryBlogUser(blog);

        //查询blog是否点过赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录 无需查询是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        String key = "blog:like:" + blog.getId();
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 判断当前用户是否已经点赞
        String key = "blog:like:" + id;
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 如果未点赞，可以点赞
            // 数据库点赞数+1
            LambdaUpdateWrapper<Blog> updateWrapper = new LambdaUpdateWrapper();
            updateWrapper.set(Blog::getLiked, this.getById(id).getLiked() + 1);
            updateWrapper.eq(Blog::getId, this.getById(id).getId());
            boolean success = this.update(updateWrapper);
            // 保存用户到redis集合
            if (success) {
                redisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 如果已经点赞 取消点赞
            // 数据库点赞数-1
            LambdaUpdateWrapper<Blog> updateWrapper = new LambdaUpdateWrapper();
            updateWrapper.set(Blog::getLiked, this.getById(id).getLiked() - 1);
            updateWrapper.eq(Blog::getId, this.getById(id).getId());
            boolean success = this.update(updateWrapper);
            // 把用户把redis的set集合里删除
            if (success) {
                redisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(long id) {
        // 查询Top5的点赞用户
        Set<String> top5 = redisTemplate.opsForZSet().range("blog:like:" + id, 0, -1);
        if (top5==null || top5.isEmpty()){
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>();
        for (Object o : top5) {
            // 解析出其中的用户ID
            ids.add(Long.valueOf((String)o));
        }
//        List<Long> ids =top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 根据用户ID查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = this.save(blog);
        // 查询笔记作者的所有粉丝
        if (!isSuccess){
            return Result.fail("新增笔记失败");
        }
        // 推送笔记ID给所有粉丝
        LambdaUpdateWrapper<Follow> lambdaUpdateWrapper=new LambdaUpdateWrapper<>();
        lambdaUpdateWrapper.eq(Follow::getFollowUserId,user.getId());
        List<Follow> follows = followService.list(lambdaUpdateWrapper);
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key="feed:"+userId;
            redisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());

        }
        // 返回id
        return Result.ok(blog.getId());
    }

    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 查询收件箱
        String key="feed:"+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 非空判断
        if (typedTuples==null|| typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 解析数据 ： blogId，score（时间戳）、offset偏移量
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 获取id
            ids.add(Long.valueOf(typedTuple.getValue()));
            // 获取分数（时间戳）
            long time=typedTuple.getScore().longValue();
            if (time==minTime){
                os++;
            }else {
                minTime = time;
                os=1;
            }

        }
        // 根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = this.query().in("id", ids).last("ORDER BY FIELD(id,"+ idStr+")").list();
        for (Blog blog : blogs) {
            //查询用户
            queryBlogUser(blog);
            //查询blog是否点过赞
            isBlogLiked(blog);
        }
        // 封装并且返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(offset);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
