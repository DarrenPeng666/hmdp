package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInteceptor implements HandlerInterceptor {

    private RedisTemplate redisTemplate;

    public RefreshTokenInteceptor(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1 获取请求头中的token
        String token = request.getHeader("authorization");
        if (token == null) {
            return true;
        }
        //2 基于token获取redis中的用户
        Map userMap = redisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        if (userMap == null) {
            return true;
        }
        //4 将查询的Hash数据转换为UserDto对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //5 保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        //6 刷新token有效期
        redisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
        //7 放行
        return true;

    }

    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
