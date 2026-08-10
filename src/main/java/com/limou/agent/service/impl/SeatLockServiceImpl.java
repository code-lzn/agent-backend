package com.limou.agent.service.impl;

import com.limou.agent.mapper.SeatMapper;
import com.limou.agent.model.entity.Seat;
import com.limou.agent.model.enums.SeatStatusEnum;
import com.limou.agent.model.vo.SeatLockResult;
import com.limou.agent.service.SeatLockService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 座位锁定服务实现：Redis 分布式锁（互斥）+ 数据库乐观锁（只锁 available）。
 * <p>
 * 相比 FOR UPDATE 行锁：行锁是阻塞等待，Redis 锁是快速失败（tryLock 3 秒拿不到即返回冲突）；
 * 乐观锁保证同一座位只有第一个把 status 从 available 改成 locked 的人成功。
 * <p>
 * 并发安全：lockOwner 参数用于标记锁的归属——同一 owner 重复锁同一座位时幂等放行，
 * 不同 owner 尝试锁已 locked 座位时拒绝，防止 A 锁座后 B 绕过互斥锁（A 的 Redis 锁已释放但 DB 仍为 locked）。
 *
 * @author 李振南
 */
@Service
@Slf4j
public class SeatLockServiceImpl implements SeatLockService {

    private static final String LOCK_KEY_PREFIX = "seat:lock:";
    private static final String OWNER_KEY_PREFIX = "seat:owner:";
    private static final long WAIT_SECONDS = 3;
    /** Redis 互斥锁只用于瞬时互斥（秒级），DB status + owner key 才是持久状态 */
    private static final long MUTEX_SECONDS = 5;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private SeatMapper seatMapper;

    @Override
    public SeatLockResult lockSeats(Long scheduleId, List<Long> seatIds, int leaseMinutes, String lockOwner) {
        SeatLockResult result = new SeatLockResult();
        if (scheduleId == null || seatIds == null || seatIds.isEmpty()) {
            result.setSuccess(false);
            return result;
        }

        // 1. 查询座位（校验存在 + 拿标签/区域）
        List<Seat> seats = seatMapper.selectListByQuery(
                QueryWrapper.create().eq("scheduleId", scheduleId).in("id", seatIds));
        if (seats.size() != new HashSet<>(seatIds).size()) {
            result.setSuccess(false);
            result.setConflictSeatIds(seatIds);
            result.setConflictSeatLabels(List.of("部分座位不存在，请刷新后重试"));
            return result;
        }

        // 2. 按 ID 排序，避免多座位并发死锁
        List<Seat> sorted = seats.stream()
                .sorted(Comparator.comparing(Seat::getId))
                .collect(Collectors.toList());

        // 3. 获取所有 Redis 互斥锁（任一失败则释放已拿的并返回冲突）
        List<RLock> acquiredLocks = new ArrayList<>();
        try {
            for (Seat seat : sorted) {
                RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + scheduleId + ":" + seat.getId());
                if (lock.tryLock(WAIT_SECONDS, MUTEX_SECONDS, TimeUnit.SECONDS)) {
                    acquiredLocks.add(lock);
                } else {
                    releaseLocks(acquiredLocks);
                    result.setSuccess(false);
                    result.setConflictSeatIds(List.of(seat.getId()));
                    result.setConflictSeatLabels(List.of(seat.getSeatLabel() + " 正在被他人锁定"));
                    return result;
                }
            }

            // 4. 逐座位处理：available → locked（乐观锁）；已 locked → 校验归属
            List<Seat> locked = new ArrayList<>();
            List<Long> conflictIds = new ArrayList<>();
            List<String> conflictLabels = new ArrayList<>();
            int ownerTtlSeconds = Math.max(leaseMinutes, 1) * 60;

            for (Seat seat : sorted) {
                if (SeatStatusEnum.LOCKED.getValue().equals(seat.getStatus())) {
                    // ★ 座位已是 locked → 校验归属，防止 A 锁座后 B 绕过互斥锁抢占
                    String ownerKey = OWNER_KEY_PREFIX + scheduleId + ":" + seat.getId();
                    String existingOwner = (String) redissonClient.getBucket(ownerKey).get();
                    if (lockOwner != null && lockOwner.equals(existingOwner)) {
                        // 同一 owner → 幂等跳过（如 lockSeat → createOrder 连续调用）
                        locked.add(seat);
                    } else {
                        // 不同 owner 或 owner 缺失（TTL 到期后被他人抢占的窗口期）→ 冲突
                        conflictIds.add(seat.getId());
                        conflictLabels.add(seat.getSeatLabel() + " 已被锁定");
                    }
                    continue;
                }
                if (!SeatStatusEnum.AVAILABLE.getValue().equals(seat.getStatus())) {
                    conflictIds.add(seat.getId());
                    conflictLabels.add(seat.getSeatLabel() + " 已被占用");
                    continue;
                }
                // 乐观锁：只有 status=available 的才能改成 locked
                int updated = seatMapper.updateByQuery(
                        Seat.builder().status(SeatStatusEnum.LOCKED.getValue()).build(),
                        QueryWrapper.create()
                                .eq("id", seat.getId())
                                .eq("status", SeatStatusEnum.AVAILABLE.getValue()));
                if (updated > 0) {
                    seat.setStatus(SeatStatusEnum.LOCKED.getValue());
                    // ★ 写入归属标记，TTL 与锁座租约对齐
                    redissonClient.getBucket(OWNER_KEY_PREFIX + scheduleId + ":" + seat.getId())
                            .set(lockOwner, ownerTtlSeconds, TimeUnit.SECONDS);
                    locked.add(seat);
                } else {
                    conflictIds.add(seat.getId());
                    conflictLabels.add(seat.getSeatLabel() + " 已被占用");
                }
            }

            if (!conflictIds.isEmpty()) {
                // 回滚本次已锁定的座位 + 清除归属标记
                for (Seat seat : locked) {
                    seatMapper.updateByQuery(
                            Seat.builder().status(SeatStatusEnum.AVAILABLE.getValue()).build(),
                            QueryWrapper.create().eq("id", seat.getId()).eq("status", SeatStatusEnum.LOCKED.getValue()));
                    redissonClient.getBucket(OWNER_KEY_PREFIX + scheduleId + ":" + seat.getId()).delete();
                }
                releaseLocks(acquiredLocks);
                result.setSuccess(false);
                result.setConflictSeatIds(conflictIds);
                result.setConflictSeatLabels(conflictLabels);
                return result;
            }

            result.setSuccess(true);
            result.setLockedSeats(locked);
            // DB 已落盘，释放 Redis 互斥锁（归属由 owner key 持有，TTL 对齐租约）
            releaseLocks(acquiredLocks);
            return result;
        } catch (Exception e) {
            log.error("Redis 锁座异常 scheduleId={}, seats={}", scheduleId, seatIds, e);
            releaseLocks(acquiredLocks);
            result.setSuccess(false);
            result.setConflictSeatLabels(List.of("锁座失败，请稍后重试"));
            return result;
        }
    }

    @Override
    public void releaseSeats(Long scheduleId, List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return;
        }
        for (Long seatId : seatIds) {
            try {
                redissonClient.getLock(LOCK_KEY_PREFIX + scheduleId + ":" + seatId).forceUnlock();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void releaseSeatsToAvailable(Long scheduleId, List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return;
        }
        for (Long seatId : seatIds) {
            // ★ 同时匹配 locked 和 sold：取消订单时座位是 locked，退款时座位已是 sold
            seatMapper.updateByQuery(
                    Seat.builder().status(SeatStatusEnum.AVAILABLE.getValue()).build(),
                    QueryWrapper.create().eq("id", seatId).in("status", List.of(
                            SeatStatusEnum.LOCKED.getValue(), SeatStatusEnum.SOLD.getValue())));
            // ★ 清除归属标记
            redissonClient.getBucket(OWNER_KEY_PREFIX + scheduleId + ":" + seatId).delete();
        }
    }

    private void releaseLocks(List<RLock> locks) {
        for (RLock lock : locks) {
            try {
                lock.forceUnlock();
            } catch (Exception ignored) {
            }
        }
    }
}
