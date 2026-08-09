package com.limou.agent.util;

import com.limou.agent.model.entity.Schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 场次时间工具：统一处理「跨天场次」的散场时间。
 * <p>
 * 跨天场次（endTime &lt;= startTime，如 23:00 开场、次日 01:45 散场）的 endTime 只有时分秒，
 * 判断「已散场/已过期」时必须把 endTime 拼到次日的日期，否则会被当成当天凌晨而误判已散场。
 * 所有对散场时间的判断都应走这里，保证跨天场次与普通场次行为一致。
 *
 * @author 李振南
 */
public final class ScheduleTimeUtil {

    private ScheduleTimeUtil() {
    }

    /**
     * 场次散场时间（跨天场次自动 +1 天）。
     *
     * @return 散场时间；showDate/startTime/endTime 任一为空或解析失败返回 null
     */
    public static LocalDateTime endDateTime(Schedule schedule) {
        if (schedule == null || schedule.getShowDate() == null
                || schedule.getStartTime() == null || schedule.getEndTime() == null) {
            return null;
        }
        try {
            LocalDate date = schedule.getShowDate().toLocalDate();
            LocalTime start = LocalTime.parse(schedule.getStartTime());
            LocalTime end = LocalTime.parse(schedule.getEndTime());
            LocalDateTime endDateTime = LocalDateTime.of(date, end);
            // 散场时刻不晚于开场时刻（endTime <= startTime）→ 跨天场次，散场在次日
            if (!end.isAfter(start)) {
                endDateTime = endDateTime.plusDays(1);
            }
            return endDateTime;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 场次是否已散场（当前时间已过散场时间）。
     * 数据缺失或解析失败返回 false（不视为已散场）。
     */
    public static boolean isEnded(Schedule schedule) {
        LocalDateTime end = endDateTime(schedule);
        return end != null && LocalDateTime.now().isAfter(end);
    }
}
