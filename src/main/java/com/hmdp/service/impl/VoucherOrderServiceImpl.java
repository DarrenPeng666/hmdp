package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedisTemplate redisTemplate;

    @Resource
    private RedissonClient redissonClient;

    public Result seckillVoucher(Long voucherId) {
        // 查询优惠卷
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        // 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始");
        }
        // 判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束");
        }

        // 判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, redisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            return Result.fail("一个人不允许重复下单！ct6");
        }
        try {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucher);

        }finally {
            lock.unlock();
        }

    }

    @Transactional
    public Result createVoucherOrder(SeckillVoucher voucher) {
        Long userId = UserHolder.getUser().getId();
        // 实现一人一单
        LambdaQueryWrapper<VoucherOrder> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        // 查询订单
        lambdaQueryWrapper.eq(VoucherOrder::getUserId, userId).eq(VoucherOrder::getVoucherId, voucher.getVoucherId());
        int count = (int) this.count(lambdaQueryWrapper);
        // 判断是否存在
        if (count > 0) {
            // 用户已经购买
            return Result.fail("用户已经购买过一次！");
        }

        // 扣减库存
        LambdaUpdateWrapper<SeckillVoucher> queryWrapper = new LambdaUpdateWrapper<>();
        queryWrapper.set(SeckillVoucher::getStock, voucher.getStock() - 1);
        queryWrapper.eq(SeckillVoucher::getVoucherId, voucher.getVoucherId());
        queryWrapper.gt(SeckillVoucher::getStock, 0);
        boolean success = iSeckillVoucherService.update(queryWrapper);
        if (!success) {
            //扣减失败
            return Result.fail("库存不足！");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单ID
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucher.getVoucherId());
        this.save(voucherOrder);
        // 返回订单id
        return Result.ok(orderId);
    }

}
