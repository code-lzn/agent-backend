package com.limou.agent.service;

import com.limou.agent.model.vo.SeatLockResult;

import java.util.List;

/**
 * 座位锁定服务：Redis 分布式锁 + 数据库乐观锁，替代 FOR UPDATE 行锁。
 * 供 C 端购票使用，后续可包装成 AI 工具（LockSeatsTool）供智能体调用。
 *
 * @author 李振南
 */
public interface SeatLockService {

    /**
     * 锁定座位（Redis 互斥 + 乐观锁，只锁 available 状态）。
     *
     * @param scheduleId   场次ID
     * @param seatIds      座位ID列表
     * @param leaseMinutes Redis 锁租期（分钟），与锁座时长/订单超时对齐
     * @param lockOwner    锁归属标识（用户ID 或会话ID），用于幂等校验——同一 owner 重复锁同一个座位时放行，不同 owner 则拒绝
     * @return 锁定结果：success=true 时含 lockedSeats；false 时含冲突座位
     */
    SeatLockResult lockSeats(Long scheduleId, List<Long> seatIds, int leaseMinutes, String lockOwner);

    /**
     * 释放座位 Redis 锁（forceUnlock，不改变座位状态）。
     * 用于下单后续步骤失败时释放锁（座位状态由事务回滚）。
     */
    void releaseSeats(Long scheduleId, List<Long> seatIds);

    /**
     * 释放座位 Redis 锁并把座位改回 available。
     * 用于订单超时/取消/退款后的座位释放。
     */
    void releaseSeatsToAvailable(Long scheduleId, List<Long> seatIds);
}
