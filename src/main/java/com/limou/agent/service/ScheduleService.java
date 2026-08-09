package com.limou.agent.service;

import com.limou.agent.model.dto.schedule.ConflictCheckRequest;
import com.limou.agent.model.entity.Film;
import com.limou.agent.model.entity.Schedule;
import com.limou.agent.model.vo.ScheduleVO;
import com.mybatisflex.core.service.IService;

import java.sql.Date;
import java.util.List;

/**
 * 排期 服务层。
 *
 * @author 李振南
 */
public interface ScheduleService extends IService<Schedule> {

    /**
     * 查询排期列表（按影院分组，含关联名称）。
     */
    List<ScheduleVO> queryScheduleList(Long filmId, Long cinemaId, Date showDate);

    /**
     * 排期冲突校验。
     *
     * @param request 冲突校验请求
     * @return true 有冲突, false 无冲突
     */
    boolean checkConflict(ConflictCheckRequest request);

    /**
     * 保存排期并自动初始化座位。
     *
     * @param schedule 排期信息
     * @return 排期ID
     */
    Long saveScheduleWithSeats(Schedule schedule);
    /** 影院当前热映影片 */
    List<Film> getCinemaHotFilms(Long cinemaId);

    /**
     * 批量保存排期并一次性初始化座位（单事务，影厅模板只解析一次）。
     *
     * @param scheduleList 排期列表
     * @return 成功创建的排期数量
     */
    int batchSaveWithSeats(List<Schedule> scheduleList);

    /**
     * 校验排期散场时间不超过午夜（开场 + 片长 + 15 分钟散场，跨天则抛异常）。
     * 新增/批量/编辑共用，从源头禁止跨天场次。
     *
     * @param schedule 排期信息（需含 filmId、startTime）
     */
    void validateEndTimeWithinDay(Schedule schedule);
}
