package com.limou.agent.task;

import com.limou.agent.mapper.TicketMapper;
import com.limou.agent.model.entity.Schedule;
import com.limou.agent.model.entity.Ticket;
import com.limou.agent.model.enums.TicketStatusEnum;
import com.limou.agent.service.ScheduleService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 电影票过期定时任务：
 * - 已结束场次下仍未核销（status=0）的票，批量置为「已过期」(3)。
 * - 启动时立即补偿执行一次，兜底后端错过凌晨 3:45 定时窗口的情况。
 *
 * @author 李振南
 */
@Component
@Slf4j
public class TicketExpireTask implements ApplicationRunner {

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private ScheduleService scheduleService;

    /**
     * 应用启动时立即执行一次过期补偿（幂等：只对 status=0 且场次已结束的票生效）。
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("票过期任务：应用启动，执行过期补偿");
        try {
            expireFinishedTickets();
        } catch (Exception e) {
            log.error("票过期任务：启动补偿执行失败", e);
        }
    }

    /**
     * 每天 3:45 执行（错开 3:00 影片上架 / 3:30 影片下线）。
     */
    @Scheduled(cron = "0 45 3 * * ?")
    public void expireTickets() {
        expireFinishedTickets();
    }

    /**
     * 核心：已结束场次的未核销票 → 已过期(3)。
     */
    public void expireFinishedTickets() {
        LocalDate today = LocalDate.now();
        String nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

        // 1. 已结束场次：日期早于今天，或（今天 且 非跨天 且 散场时间 <= 当前时间）
        //    跨天场次（startTime >= endTime，如 23:00 开场次日散场）今天不算结束，次日由 showDate < today 覆盖
        List<Schedule> endedSchedules = scheduleService.list(
                QueryWrapper.create()
                        .le("showDate", Date.valueOf(today))
                        .and("(showDate < ? OR (showDate = ? AND startTime < endTime AND endTime <= ?))",
                                Date.valueOf(today), Date.valueOf(today), nowTime));
        if (endedSchedules.isEmpty()) {
            return;
        }

        List<Long> endedScheduleIds = endedSchedules.stream()
                .map(Schedule::getId).collect(Collectors.toList());

        // 2. 这些场次下 status=0（未使用）的票批量置为已过期，幂等
        Ticket update = new Ticket();
        update.setStatus(TicketStatusEnum.EXPIRED.getValue());
        int n = ticketMapper.updateByQuery(update,
                QueryWrapper.create()
                        .in("scheduleId", endedScheduleIds)
                        .eq("status", TicketStatusEnum.UNUSED.getValue()));
        log.info("票过期任务：{} 个已结束场次，{} 张未使用票标记为已过期", endedSchedules.size(), n);
    }
}
