package com.limou.agent.controller;

import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.constant.UserConstant;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.exception.ThrowUtils;
import com.limou.agent.model.dto.schedule.ConflictCheckRequest;
import com.limou.agent.model.entity.Cinema;
import com.limou.agent.model.entity.Film;
import com.limou.agent.model.vo.ScheduleVO;
import cn.hutool.core.collection.CollUtil;
import com.limou.agent.service.CinemaService;
import com.limou.agent.service.FilmService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.limou.agent.model.entity.Schedule;
import com.limou.agent.service.ScheduleService;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 排期 控制层。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/schedule")
public class ScheduleController {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private CinemaService cinemaService;

    @Autowired
    private FilmService filmService;

    // ========== 前台接口 ==========

    /**
     * 排期列表（按影院分组，含关联信息）。
     */
    @GetMapping("/list")
    public BaseResponse<List<ScheduleVO>> listSchedule(
            @RequestParam(required = false) Long filmId,
            @RequestParam(required = false) Long cinemaId,
            @RequestParam(required = false) Date showDate) {
        ThrowUtils.throwIf(filmId == null && cinemaId == null,
                ErrorCode.PARAMS_ERROR, "影片ID和影院ID不能同时为空");
        List<ScheduleVO> list = scheduleService.queryScheduleList(filmId, cinemaId, showDate);
        return ResultUtils.success(list);
    }

    /**
     * 查询指定日期有排片的影院及其影片（排除指定影片）。
     * 用于当前影片无排片时，推荐同日期其他有场次的影片。
     */
    @GetMapping("/other-films")
    public BaseResponse<List<Map<String, Object>>> otherFilmsByDate(
            @RequestParam Date showDate,
            @RequestParam Long excludeFilmId) {
        List<Schedule> schedules = scheduleService.list(
                QueryWrapper.create()
                        .eq("status", "published")
                        .eq("showDate", showDate)
                        .ne("filmId", excludeFilmId));

        if (CollUtil.isEmpty(schedules)) {
            return ResultUtils.success(Collections.emptyList());
        }

        Set<Long> cinemaIds = schedules.stream().map(Schedule::getCinemaId).collect(Collectors.toSet());
        Set<Long> filmIds = schedules.stream().map(Schedule::getFilmId).collect(Collectors.toSet());

        Map<Long, Cinema> cinemaMap = cinemaService.listByIds(cinemaIds).stream()
                .collect(Collectors.toMap(Cinema::getId, c -> c, (a, b) -> a));
        Map<Long, Film> filmMap = filmService.listByIds(filmIds).stream()
                .collect(Collectors.toMap(Film::getId, f -> f, (a, b) -> a));

        Map<Long, Set<Long>> cinemaFilmMap = new LinkedHashMap<>();
        for (Schedule s : schedules) {
            cinemaFilmMap.computeIfAbsent(s.getCinemaId(), k -> new LinkedHashSet<>())
                    .add(s.getFilmId());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> entry : cinemaFilmMap.entrySet()) {
            Long cid = entry.getKey();
            Cinema cinema = cinemaMap.get(cid);
            if (cinema == null) continue;

            List<Map<String, Object>> films = entry.getValue().stream()
                    .map(fid -> {
                        Film f = filmMap.get(fid);
                        if (f == null) return null;
                        Map<String, Object> fm = new LinkedHashMap<>();
                        fm.put("id", f.getId().toString());
                        fm.put("name", f.getName());
                        fm.put("posterUrl", f.getPosterUrl() != null ? f.getPosterUrl() : "");
                        return fm;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("cinemaId", cid.toString());
            cm.put("cinemaName", cinema.getName() != null ? cinema.getName() : "");
            cm.put("address", cinema.getAddress() != null ? cinema.getAddress() : "");
            cm.put("films", films);
            result.add(cm);
        }

        return ResultUtils.success(result);
    }

    // ========== 后台管理接口 ==========

    /**
     * 保存排期（含自动初始化座位）。
     */
    @PostMapping("save")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> save(@RequestBody Schedule schedule) {
        ThrowUtils.throwIf(schedule == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(schedule.getFilmId() == null, ErrorCode.PARAMS_ERROR, "影片不能为空");
        ThrowUtils.throwIf(schedule.getCinemaId() == null, ErrorCode.PARAMS_ERROR, "影院不能为空");
        ThrowUtils.throwIf(schedule.getHallId() == null, ErrorCode.PARAMS_ERROR, "影厅不能为空");
        ThrowUtils.throwIf(schedule.getShowDate() == null || schedule.getStartTime() == null,
                ErrorCode.PARAMS_ERROR, "日期和时间不能为空");
        Long id = scheduleService.saveScheduleWithSeats(schedule);
        return ResultUtils.success(id);
    }

    /**
     * 批量创建排期。
     */
    @PostMapping("/batchSave")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> batchSave(@RequestBody List<Schedule> scheduleList) {
        ThrowUtils.throwIf(CollUtil.isEmpty(scheduleList), ErrorCode.PARAMS_ERROR);
        int count = scheduleService.batchSaveWithSeats(scheduleList);
        return ResultUtils.success(count);
    }

    /**
     * 排期冲突校验。
     */
    @PostMapping("/checkConflict")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> checkConflict(@RequestBody ConflictCheckRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        boolean hasConflict = scheduleService.checkConflict(request);
        return ResultUtils.success(hasConflict);
    }

    /**
     * 根据主键删除。
     */
    @DeleteMapping("remove/{id}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> remove(@PathVariable Long id) {
        return ResultUtils.success(scheduleService.removeById(id));
    }

    /**
     * 根据主键更新。
     */
    @PutMapping("update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> update(@RequestBody Schedule schedule) {
        // 编辑同样禁止跨天场次：开场 + 片长 + 15 分钟散场超过午夜则拒绝
        scheduleService.validateEndTimeWithinDay(schedule);
        boolean result = scheduleService.updateById(schedule);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 后台分页查询。
     */
    @PostMapping("page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Schedule>> page(@RequestBody Page<Schedule> page) {
        return ResultUtils.success(scheduleService.page(page));
    }

    /**
     * 根据主键获取。
     */
    @GetMapping("getInfo/{id}")
    public BaseResponse<Schedule> getInfo(@PathVariable Long id) {
        return ResultUtils.success(scheduleService.getById(id));
    }

    /**
     * 查询所有。
     */
    @GetMapping("listAll")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<Schedule>> listAll() {
        return ResultUtils.success(scheduleService.list());
    }

    /**
     * 影院当前热映影片（有排片且状态为hot）
     */
    @GetMapping("/cinema-films")
    public BaseResponse<List<Film>> cinemaFilms(@RequestParam Long cinemaId) {
        ThrowUtils.throwIf(cinemaId == null || cinemaId <= 0, ErrorCode.PARAMS_ERROR);
        List<Film> films = scheduleService.getCinemaHotFilms(cinemaId);
        return ResultUtils.success(films);
    }
}
