package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private RedisTemplate redisTemplate;
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
        if (user==null){
            // 用户未登录 无需查询是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        String key = "blog:like:" + blog.getId();
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
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
        Set top5 = redisTemplate.opsForZSet().range("blog:like:" + id, 0, 4);
        List<Long> ids=new ArrayList<>();
        for (Object o : top5) {
            // 解析出其中的用户ID
            ids.add(Long.parseLong((String) o));
        }
        // 根据用户ID查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 返回

        return Result.ok(userDTOS);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
