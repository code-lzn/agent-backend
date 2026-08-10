package com.limou.agent.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.limou.agent.exception.BusinessException;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.mapper.FilmMapper;
import com.limou.agent.model.dto.schedule.ConflictCheckRequest;
import com.limou.agent.model.entity.Cinema;
import com.limou.agent.model.entity.Film;
import com.limou.agent.model.entity.Hall;
import com.limou.agent.model.entity.Seat;
import com.limou.agent.model.entity.Schedule;
import com.limou.agent.model.enums.SeatStatusEnum;
import com.limou.agent.model.vo.ScheduleVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.limou.agent.mapper.ScheduleMapper;
import com.limou.agent.mapper.SeatMapper;
import com.limou.agent.service.*;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 排期 服务层实现。
 *
 * @author 李振南
 */
@Service
public class ScheduleServiceImpl extends ServiceImpl<ScheduleMapper, Schedule> implements ScheduleService {

    @Autowired
    private FilmService filmService;

    @Autowired
    private CinemaService cinemaService;

    @Autowired
    private HallService hallService;

    @Autowired
    @Lazy
    private SeatService seatService;
    @Resource
    private FilmMapper filmMapper;

    @Autowired
    private SeatMapper seatMapper;

    @Override
    public List<ScheduleVO> queryScheduleList(Long filmId, Long cinemaId, Date showDate) {
        if (filmId == null && cinemaId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "影片ID和影院ID不能同时为空");
        }

        Date queryDate = showDate != null ? showDate : Date.valueOf(LocalDate.now());

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("status", "published")
                .ge("showDate", queryDate)
                .orderBy("showDate", true)
                .orderBy("startTime", true);

        if (filmId != null) {
            queryWrapper.eq("filmId", filmId);
        }
        // 指定日期 → 精确匹配当天（C端日期 Tab）；未指定 → 从今天起全部未来场次
        if (showDate != null) {
            queryWrapper.eq("showDate", queryDate);
        } else {
            queryWrapper.ge("showDate", queryDate);
        }

        if (cinemaId != null) {
            queryWrapper.eq("cinemaId", cinemaId);
        }

        List<Schedule> scheduleList = mapper.selectListByQuery(queryWrapper);
        if (CollUtil.isEmpty(scheduleList)) {
            return new ArrayList<>();
        }

        // ★ 过滤已过时的场次（今天已开场的场次不再展示）
        LocalDateTime now = LocalDateTime.now();
        scheduleList = scheduleList.stream()
                .filter(s -> {
                    if (s.getShowDate() == null || s.getStartTime() == null) return true;
                    try {
                        LocalDateTime showDateTime = LocalDateTime.of(
                                s.getShowDate().toLocalDate(),
                                LocalTime.parse(s.getStartTime().toString()));
                        return showDateTime.isAfter(now);
                    } catch (Exception e) {
                        return true; // 解析失败保留，避免误杀
                    }
                })
                .collect(Collectors.toList());

        if (CollUtil.isEmpty(scheduleList)) {
            return new ArrayList<>();
        }

        Set<Long> filmIds = new HashSet<>();
        Set<Long> cinemaIds = new HashSet<>();
        Set<Long> hallIds = new HashSet<>();
        for (Schedule s : scheduleList) {
            filmIds.add(s.getFilmId());
            cinemaIds.add(s.getCinemaId());
            hallIds.add(s.getHallId());
        }

        Map<Long, Film> filmMap = filmService.listByIds(filmIds).stream()
                .collect(Collectors.toMap(Film::getId, f -> f, (a, b) -> a));
        Map<Long, Cinema> cinemaMap = cinemaService.listByIds(cinemaIds).stream()
                .collect(Collectors.toMap(Cinema::getId, c -> c, (a, b) -> a));
        Map<Long, Hall> hallMap = hallService.listByIds(hallIds).stream()
                .collect(Collectors.toMap(Hall::getId, h -> h, (a, b) -> a));

        // ★ 过滤即将上映(upcoming)影片的场次：未上映影片不提供购票（删按钮后堵住直接访问选座页的旁路）
        scheduleList.removeIf(s -> {
            Film film = filmMap.get(s.getFilmId());
            return film != null && "upcoming".equals(film.getStatus());
        });

        List<ScheduleVO> voList = new ArrayList<>();
        for (Schedule schedule : scheduleList) {
            ScheduleVO vo = new ScheduleVO();
            BeanUtil.copyProperties(schedule, vo);

            Film film = filmMap.get(schedule.getFilmId());
            if (film != null) {
                vo.setFilmName(film.getName());
                vo.setFilmPoster(film.getPosterUrl());
                vo.setFilmDuration(film.getDuration());
                vo.setFilmRating(film.getRating() != null ? film.getRating().toString() : null);
                vo.setFilmType(film.getType());
            }

            Cinema cinema = cinemaMap.get(schedule.getCinemaId());
            if (cinema != null) {
                vo.setCinemaName(cinema.getName());
                vo.setCinemaAddress(cinema.getAddress());
            }

            Hall hall = hallMap.get(schedule.getHallId());
            if (hall != null) {
                vo.setHallName(hall.getName());
                vo.setHallType(hall.getHallType());
                vo.setHallRowCount(hall.getRowCount());
                vo.setHallColCount(hall.getColCount());
            }

            voList.add(vo);
        }

        return voList;
    }

    @Override
    public boolean checkConflict(ConflictCheckRequest request) {
        if (request.getHallId() == null || request.getShowDate() == null
                || request.getStartTime() == null || request.getEndTime() == null) {
            return false;
        }

        // 同影厅、同日期、时段重叠检测
        // 冲突条件: 已有排期的 startTime < 新排期的 endTime 且 已有排期的 endTime > 新排期的 startTime
        QueryWrapper qw = QueryWrapper.create()
                .eq("hallId", request.getHallId())
                .eq("showDate", request.getShowDate())
                .ne("status", "offline")
                .and("startTime < ?", request.getEndTime())
                .and("endTime > ?", request.getStartTime());

        // 编辑排期时排除自身
        if (request.getExcludeScheduleId() != null) {
            qw.ne("id", request.getExcludeScheduleId());
        }

        return mapper.selectCountByQuery(qw) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveScheduleWithSeats(Schedule schedule) {
        // 0. 校验日期不能为过去 + 影院营业状态 + 影片可上映状态 + 散场时间不超过午夜
        checkShowDateNotPast(schedule.getShowDate());
        checkCinemaOperable(schedule.getCinemaId());
        checkFilmPublishable(schedule.getFilmId());
        checkEndTimeWithinDay(schedule, null);

        // 1. 保存排期
        boolean saved = super.save(schedule);
        if (!saved) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "排期保存失败");
        }

        // 2. 查影厅信息
        Hall hall = hallService.getById(schedule.getHallId());
        if (hall == null || hall.getRowCount() == null || hall.getColCount() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "影厅信息不完整，无法初始化座位");
        }

        // 3. 解析 seatTemplate 并初始化座位（多行插入，一次往返）
        HallLayout layout = parseHallLayout(hall);
        seatMapper.batchInsertSeats(buildSeats(schedule, hall.getId(), layout));
        return schedule.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchSaveWithSeats(List<Schedule> scheduleList) {
        if (CollUtil.isEmpty(scheduleList)) {
            return 0;
        }

        // 0. 校验影院营业状态（去重检查）+ 日期不能为过去 + 影片可上映状态 + 散场时间不超过午夜，先于保存，快速失败
        Set<Long> checkedCinemas = new HashSet<>();
        Set<Long> checkedFilms = new HashSet<>();
        Map<Long, Film> filmCache = new HashMap<>();
        for (Schedule s : scheduleList) {
            if (checkedCinemas.add(s.getCinemaId())) {
                checkCinemaOperable(s.getCinemaId());
            }
            checkShowDateNotPast(s.getShowDate());
            if (checkedFilms.add(s.getFilmId())) {
                checkFilmPublishable(s.getFilmId());
            }
            checkEndTimeWithinDay(s, filmCache);
        }

        // 1. 批量保存所有场次，拿到自增 ID（单事务 + 单批 SQL，避免逐条 INSERT 的网络往返）
        boolean batchSaved = super.saveBatch(scheduleList);
        if (!batchSaved) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "排期保存失败");
        }

        // 2. 按影厅一次性解析 seatTemplate（避免每条场次重复查厅/解析）
        Map<Long, HallLayout> layoutMap = new HashMap<>();
        for (Schedule s : scheduleList) {
            if (layoutMap.containsKey(s.getHallId())) {
                continue;
            }
            Hall hall = hallService.getById(s.getHallId());
            if (hall == null || hall.getRowCount() == null || hall.getColCount() == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "影厅信息不完整，无法初始化座位");
            }
            layoutMap.put(hall.getId(), parseHallLayout(hall));
        }

        // 3. 为所有场次生成座位
        List<Seat> allSeats = new ArrayList<>();
        for (Schedule s : scheduleList) {
            HallLayout layout = layoutMap.get(s.getHallId());
            if (layout == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "影厅信息不完整，无法初始化座位");
            }
            allSeats.addAll(buildSeats(s, s.getHallId(), layout));
        }

        // 4. 一次性多行插入所有座位（单条 SQL、一次往返，避免逐行插入）
        seatMapper.batchInsertSeats(allSeats);
        return scheduleList.size();
    }

    /**
     * 校验影院营业状态：仅「营业中(published)」的影院允许新增场次。
     */
    private void checkCinemaOperable(Long cinemaId) {
        if (cinemaId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "影院不能为空");
        }
        Cinema cinema = cinemaService.getById(cinemaId);
        if (cinema == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "影院不存在");
        }
        if (!"published".equals(cinema.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "影院「" + cinema.getName() + "」当前" + cinemaStatusText(cinema.getStatus()) + "，无法新增场次");
        }
    }

    /**
     * 校验影片可排片：即将上映(upcoming)的影片拒绝新增场次。
     */
    private void checkFilmPublishable(Long filmId) {
        if (filmId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "影片不能为空");
        }
        Film film = filmService.getById(filmId);
        if (film == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "影片不存在");
        }
        if ("upcoming".equals(film.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "影片「" + film.getName() + "」尚未上映，无法排片");
        }
    }

    private String cinemaStatusText(String status) {
        if ("published".equals(status)) {
            return "营业中";
        }
        if ("offline".equals(status)) {
            return "已停业";
        }
        return "未营业";
    }

    /**
     * 校验放映日期不能是过去（今天及以后才允许排片）。
     */
    private void checkShowDateNotPast(Date showDate) {
        if (showDate != null && showDate.before(Date.valueOf(LocalDate.now()))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能新增过去的场次");
        }
    }

    @Override
    public void validateEndTimeWithinDay(Schedule schedule) {
        checkEndTimeWithinDay(schedule, null);
    }

    /**
     * 校验散场时间不超过午夜：开场时间 + 影片时长 + 15 分钟散场，若跨过 24:00 则拒绝新增。
     * 从源头禁止跨天场次（避免跨天场次的票过期/座位清理误判）。
     */
    private void checkEndTimeWithinDay(Schedule schedule, Map<Long, Film> filmCache) {
        if (schedule == null || schedule.getFilmId() == null || schedule.getStartTime() == null) {
            return;
        }
        Film film = filmCache != null ? filmCache.get(schedule.getFilmId())
                : filmService.getById(schedule.getFilmId());
        if (film == null || film.getDuration() == null) {
            return; // 无片长信息时跳过（由影片可上映校验兜底）
        }
        LocalTime start;
        try {
            start = LocalTime.parse(schedule.getStartTime());
        } catch (Exception e) {
            return; // 时间格式异常由其他校验兜底
        }
        // 开场 + 片长 + 15 分钟散场，超过 24:00 即跨天
        LocalTime end = start.plusMinutes(film.getDuration() + 15L);
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "散场时间超过午夜，不允许添加跨天场次（开场 " + schedule.getStartTime()
                            + " + 片长 " + film.getDuration() + " 分钟 + 15 分钟散场）");
        }
        if (filmCache != null) {
            filmCache.putIfAbsent(schedule.getFilmId(), film);
        }
    }

    /**
     * 影厅座位布局（从 seatTemplate 解析）。
     */
    private static class HallLayout {
        int rowCount;
        int colCount;
        Set<Integer> vipRows = new HashSet<>();
        Set<String> vipCells = new HashSet<>();
        Map<Integer, Integer> rowOverrides = new HashMap<>();
        Set<String> blockedCells = new HashSet<>();
        List<Integer> aisleRows = new ArrayList<>();
        List<Integer> aisleCols = new ArrayList<>();
    }

    private HallLayout parseHallLayout(Hall hall) {
        HallLayout layout = new HallLayout();
        layout.rowCount = hall.getRowCount();
        layout.colCount = hall.getColCount();
        if (cn.hutool.core.util.StrUtil.isNotBlank(hall.getSeatTemplate())) {
            try {
                cn.hutool.json.JSONObject tmpl = new cn.hutool.json.JSONObject(hall.getSeatTemplate());
                if (tmpl.containsKey("vipRows")) {
                    for (Object r : tmpl.getJSONArray("vipRows")) {
                        layout.vipRows.add((Integer) r);
                    }
                }
                if (tmpl.containsKey("vipCells")) {
                    for (Object c : tmpl.getJSONArray("vipCells")) {
                        layout.vipCells.add((String) c);
                    }
                }
                if (tmpl.containsKey("rowOverrides")) {
                    cn.hutool.json.JSONObject overrides = tmpl.getJSONObject("rowOverrides");
                    for (Map.Entry<String, Object> entry : overrides.entrySet()) {
                        try {
                            int r = Integer.parseInt(entry.getKey());
                            int c = ((Number) entry.getValue()).intValue();
                            layout.rowOverrides.put(r, c);
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (tmpl.containsKey("blockedCells")) {
                    for (Object c : tmpl.getJSONArray("blockedCells")) {
                        layout.blockedCells.add((String) c);
                    }
                }
                if (tmpl.containsKey("aisleRows")) {
                    for (Object r : tmpl.getJSONArray("aisleRows")) {
                        layout.aisleRows.add(((Number) r).intValue());
                    }
                }
                if (tmpl.containsKey("aisleCols")) {
                    for (Object c : tmpl.getJSONArray("aisleCols")) {
                        layout.aisleCols.add(((Number) c).intValue());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return layout;
    }

    private List<Seat> buildSeats(Schedule schedule, Long hallId, HallLayout layout) {
        List<Seat> seats = new ArrayList<>();
        for (int row = 1; row <= layout.rowCount; row++) {
            int rowCols = layout.rowOverrides.getOrDefault(row, layout.colCount);
            for (int col = 1; col <= rowCols; col++) {
                // 空位/柱子/缺口：不生成座位，colNum 仍保留物理格位置（前端按物理格遍历留白）
                if (layout.blockedCells.contains(row + "," + col)) {
                    continue;
                }
                Seat seat = new Seat();
                seat.setScheduleId(schedule.getId());
                seat.setHallId(hallId);
                seat.setRowNum(row);
                seat.setColNum(col);
                seat.setSeatLabel(row + "排" + col + "座");

                // 判断是否为 VIP 区
                boolean isVip = layout.vipRows.contains(row) || layout.vipCells.contains(row + "," + col);
                seat.setZone(isVip ? "vip" : "regular");

                seat.setStatus(SeatStatusEnum.AVAILABLE.getValue());
                seats.add(seat);
            }
        }
        return seats;
    }
    @Override
    public List<Film> getCinemaHotFilms(Long cinemaId) {
        // 1. 查排片表中该影院的 filmId（去重）
        List<Schedule> schedules = this.list(
                QueryWrapper.create()
                        .eq("cinemaId", cinemaId)
                        .eq("status", "published")
        );
        if (CollUtil.isEmpty(schedules)) return new ArrayList<>();

        Set<Long> filmIds = schedules.stream()
                .map(Schedule::getFilmId)
                .collect(Collectors.toSet());
        // 2. 查可上映影片：热映 hot + 正在上映 published 都返回（published 影片有排片也能被展示）
        return filmMapper.selectListByQuery(
                QueryWrapper.create()
                        .in("id", filmIds)
                        .in("status", List.of("hot", "published"))
        );
    }

}
