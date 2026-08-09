package com.limou.agent.task;

import com.limou.agent.mapper.ScheduleMapper;
import com.limou.agent.mapper.SeatMapper;
import com.limou.agent.model.entity.Schedule;
import com.limou.agent.model.entity.Seat;
import com.limou.agent.util.ScheduleTimeUtil;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 已结束场次的座位清理定时任务（每小时执行）。
 * <p>
 * 场次散场后，对应的座位数据不再需要展示，定时清理释放存储空间。
 * 只清理无关联订单的座位（有历史订单的座位保留记录）。
 *
 * @author 李振南
 */
@Slf4j
@Component
public class ExpiredScheduleSeatCleanupTask {

    @Resource
    private ScheduleMapper scheduleMapper;

    @Resource
    private SeatMapper seatMapper;

    /**
     * 每小时执行一次：清理已散场场次的座位。
     */
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredShowtimeSeats() {
        log.info("散场座位清理任务开始...");
        int deletedTotal = 0;
        int scheduleCount = 0;

        try {
            // 1. 查询所有已过时的场次（今天之前 + 今天但已散场）
            LocalDate today = LocalDate.now();

            // 1a. 今天之前的场次（必定已散场）
            List<Schedule> pastSchedules = scheduleMapper.selectListByQuery(
                    QueryWrapper.create()
                            .lt(Schedule::getShowDate, Date.valueOf(today))
                            .in(Schedule::getStatus, "published", "soldOut", "offline"));

            // 1b. 今天但散场时间已过的场次
            List<Schedule> todayEnded = scheduleMapper.selectListByQuery(
                    QueryWrapper.create()
                            .eq(Schedule::getShowDate, Date.valueOf(today))
                            .in(Schedule::getStatus, "published", "soldOut", "offline"));

            // 过滤：今天已散场的才清理（跨天场次今天开场、次日散场，由 isEnded 正确处理）
            todayEnded = todayEnded.stream()
                    .filter(ScheduleTimeUtil::isEnded)
                    .collect(Collectors.toList());

            List<Schedule> expired = new java.util.ArrayList<>(pastSchedules);
            expired.addAll(todayEnded);

            if (expired.isEmpty()) {
                log.info("散场座位清理：无已结束场次，跳过");
                return;
            }

            // 2. 逐场次删除座位
            for (Schedule s : expired) {
                try {
                    // 只删除没有关联订单的座位（有订单的座位保留历史记录）
                    // 生成场次的座位通常没关联订单才删除，直接查 status != 'sold' 的座位
                    long deleted = seatMapper.deleteByQuery(
                            QueryWrapper.create()
                                    .eq(Seat::getScheduleId, s.getId())
                                    .ne(Seat::getStatus, "sold"));

                    if (deleted > 0) {
                        deletedTotal += deleted;
                        scheduleCount++;
                        log.debug("散场座位清理：场次 {} ({} {}) 删除 {} 个座位",
                                s.getId(), s.getShowDate(), s.getEndTime(), deleted);
                    }
                } catch (Exception e) {
                    log.error("散场座位清理：场次 {} 删除失败", s.getId(), e);
                }
            }

        } catch (Exception e) {
            log.error("散场座位清理任务异常", e);
        } finally {
            log.info("散场座位清理任务完成：{} 个场次，共删除 {} 个座位", scheduleCount, deletedTotal);
        }
    }
}
