package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private RedisTemplate redisTemplate;

    @Override
    public Result queryAllTypes() {
//        方法1 使用opsForValue
//        String shopTypes = (String) redisTemplate.opsForValue().get("cache:shopType");
//        if (shopTypes != null) {
//            List<ShopType> shopTypeList = JSONUtil.toBean(shopTypes, ShopType.class, false);
//            return Result.ok(shopTypeList);
//        }
//        List<ShopType> shopTypeList = this.list();
//        redisTemplate.opsForValue().set("cache:shopType",JSONUtil.toJsonStr(shopTypeList));
//        return Result.ok(shopTypeList);

        //方法1.2 使用opsForValue(直接存贮对象)
        String shopTypes = (String) redisTemplate.opsForValue().get("cache:shopType");

        if (shopTypes != null) {
            List<ShopType> types = JSONUtil.toList(shopTypes, ShopType.class);
            return Result.ok(types);

        }
        List<ShopType> shopTypeList = this.list();
        redisTemplate.opsForValue().set("cache:shopType", JSONUtil.toJsonStr(shopTypeList),30, TimeUnit.MINUTES);
        return Result.ok(shopTypeList);

//        方法2使用Zset
//        Set shopTypes = redisTemplate.opsForZSet().range("cache:shopType", 0, -1);
//        if (!shopTypes.isEmpty()){
//            List<ShopType> shopTypeList=new ArrayList<>();
//            for (Object shopType : shopTypes) {
//                shopTypeList.add((ShopType) shopType);
//            }
//            return Result.ok(shopTypeList);
//        }
//        List<ShopType> shopTypeList = this.list();
//        for (ShopType shopType : shopTypeList) {
//            redisTemplate.opsForZSet().add("cache:shopType",shopType,shopType.getSort());
//        }
//        return Result.ok(shopTypeList);
    }
}
