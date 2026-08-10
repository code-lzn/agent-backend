package com.limou.agent.controller;

import com.limou.agent.annotation.AuthCheck;
import com.limou.agent.constant.UserConstant;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.exception.BusinessException;
import com.limou.agent.exception.ErrorCode;
import com.limou.agent.exception.ThrowUtils;
import com.limou.agent.model.dto.film.FilmQueryRequest;
import com.limou.agent.service.ScheduleService;
import com.limou.agent.task.FilmCacheRefresher;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.limou.agent.model.entity.Film;
import com.limou.agent.service.FilmService;
import org.springframework.web.bind.annotation.RestController;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * 影片 控制层。
 *
 * @author 李振南
 */
@RestController
@RequestMapping("/film")
public class FilmController {

    @Autowired
    private FilmService filmService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private FilmCacheRefresher filmCacheRefresher;

    // ========== 前台接口 ==========

    /**
     * 正在热映（评分最高的前 N 部已发布影片）。
     */
    @GetMapping("/now-showing")
    public BaseResponse<List<Film>> nowShowing(@RequestParam(defaultValue = "8") int limit) {
        FilmQueryRequest req = new FilmQueryRequest();
        // 热映区：正在上映(published) + 热映(hot)
        req.setStatusList(List.of("published", "hot"));
        req.setPageNum(1);
        req.setPageSize(limit);
        req.setSortField("rating");
        req.setSortOrder("descend");
        Page<Film> page = filmService.queryFilmPage(req);
        List<Film> records = page.getRecords();
        filmService.enrichFormatTags(records);
        return ResultUtils.success(records);
    }

    /**
     * 猜你喜欢（根据用户偏好类型推荐，未登录或无偏好时返回热门）。
     */
    @GetMapping("/recommended")
    public BaseResponse<List<Film>> recommended(@RequestParam(defaultValue = "4") int limit,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) java.math.BigDecimal minRating,
                                                 @RequestParam(required = false) Long excludeFilmId) {
        FilmQueryRequest req = new FilmQueryRequest();
        // 推荐：正在上映(published) + 热映(hot)
        req.setStatusList(List.of("published", "hot"));
        req.setPageNum(1);
        req.setPageSize(limit);
        if (StrUtil.isNotBlank(type)) {
            req.setType(type);
        }
        if (minRating != null) {
            req.setMinRating(minRating);
        }
        if (excludeFilmId != null) {
            req.setExcludeFilmId(excludeFilmId);
        }
        req.setSortField("rating");
        req.setSortOrder("descend");
        Page<Film> page = filmService.queryFilmPage(req);
        List<Film> records = page.getRecords();
        filmService.enrichFormatTags(records);
        return ResultUtils.success(records);
    }

    /**
     * 搜索影片（关键词模糊匹配）。
     */
    @GetMapping("/search")
    public BaseResponse<Page<Film>> search(@RequestParam String keyword,
                                            @RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "10") int pageSize) {
        FilmQueryRequest req = new FilmQueryRequest();
        req.setKeyword(keyword);
        req.setStatusList(List.of("published", "hot", "upcoming"));
        req.setPageNum(pageNum);
        req.setPageSize(pageSize);
        Page<Film> page = filmService.queryFilmPage(req);
        filmService.enrichFormatTags(page.getRecords());
        return ResultUtils.success(page);
    }

    /**
     * 影片列表（筛选 + 排序 + 分页，仅返回已发布影片）。
     */
    @GetMapping("/list")
    public BaseResponse<Page<Film>> listFilm(FilmQueryRequest filmQueryRequest) {
        // 前台默认查全部可上映影片：正在上映(published) + 热映(hot) + 准备上映(upcoming)
        if (filmQueryRequest.getStatus() == null
                && CollUtil.isEmpty(filmQueryRequest.getStatusList())) {
            filmQueryRequest.setStatusList(List.of("published", "hot", "upcoming"));
        }
        Page<Film> filmPage = filmService.queryFilmPage(filmQueryRequest);
        filmService.enrichFormatTags(filmPage.getRecords());
        return ResultUtils.success(filmPage);
    }

    /**
     * 影片详情。
     *
     * @param id 影片ID
     * @return 影片信息
     */
    @GetMapping("/{id}")
    public BaseResponse<Film> getFilm(@PathVariable Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        Film film = filmService.getById(id);
        ThrowUtils.throwIf(film == null, ErrorCode.NOT_FOUND_ERROR, "影片不存在");
        filmService.enrichFormatTags(List.of(film));
        return ResultUtils.success(film);
    }

    // ========== 后台管理接口 ==========

    /**
     * 保存。
     */
    @PostMapping("save")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> save(@RequestBody Film film) {
        validateFilmStatusChange(film);
        boolean result = filmService.save(film);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 影片新增后刷新模糊匹配索引 + RAG 向量库，无需重启即可被检索
        filmCacheRefresher.refreshAll();
        return ResultUtils.success(film.getId());
    }

    /**
     * 根据主键删除。
     */
    @DeleteMapping("remove/{id}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> remove(@PathVariable Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        // PRD 3.3.3.1 交互规则③：存在"有效"排片场次（今天及以后未放映）的影片禁止删除，仅允许下线
        long scheduleCount = scheduleService.count(QueryWrapper.create()
                .eq("filmId", id)
                .ge("showDate", Date.valueOf(LocalDate.now())));
        ThrowUtils.throwIf(scheduleCount > 0, ErrorCode.OPERATION_ERROR,
                "该影片存在未放映的排片场次，禁止删除，请先在「场次管理」中删除对应场次");
        boolean result = filmService.removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        filmCacheRefresher.refreshAll();
        return ResultUtils.success(true);
    }

    /**
     * 根据主键更新。
     */
    @PutMapping("update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> update(@RequestBody Film film) {
        validateFilmStatusChange(film);
        boolean result = filmService.updateById(film);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        filmCacheRefresher.refreshAll();
        return ResultUtils.success(true);
    }

    /**
     * 查询所有（B 端影片管理/排期页下拉用，C 端影院详情已改走 /schedule/list）。
     */
    @GetMapping("listAll")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<Film>> listAll() {
        List<Film> list = filmService.list();
        filmService.enrichFormatTags(list);
        return ResultUtils.success(list);
    }

    /**
     * 后台分页查询。
     */
    @PostMapping("page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Film>> page(@RequestBody FilmQueryRequest filmQueryRequest) {
        Page<Film> filmPage = filmService.queryFilmPage(filmQueryRequest);
        return ResultUtils.success(filmPage);
    }

    /**
     * 根据主键获取。
     */
    @GetMapping("getInfo/{id}")
    public BaseResponse<Film> getInfo(@PathVariable Long id) {
        Film film = filmService.getById(id);
        if (film != null) {
            filmService.enrichFormatTags(List.of(film));
        }
        return ResultUtils.success(film);
    }

    /**
     * 修改影片状态（后台管理）。
     */
    @PutMapping("/status/{id}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateStatus(@PathVariable Long id, @RequestParam String status) {
        Film film = filmService.getById(id);
        ThrowUtils.throwIf(film == null, ErrorCode.NOT_FOUND_ERROR);
        film.setStatus(status);
        validateFilmStatusChange(film);
        boolean result = filmService.updateById(film);
        // 状态变更影响 hot/published 集合，刷新缓存
        filmCacheRefresher.refreshAll();
        return ResultUtils.success(result);
    }

    /**
     * 影片状态流转校验：
     * 1. 未到上映日期（releaseDate 晚于今天）的影片不能设置为「热映中(hot)」；
     * 2. 下线(offline)参照删除规则——存在未放映排片场次（showDate >= 今天）的影片禁止下线。
     */
    private void validateFilmStatusChange(Film film) {
        if (film == null) {
            return;
        }
        // 规则1：未到上映日期不能设为热映中
        if ("hot".equals(film.getStatus()) && film.getReleaseDate() != null
                && film.getReleaseDate().toLocalDate().isAfter(LocalDate.now())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "影片未到上映日期（" + film.getReleaseDate() + "），不能设置为热映中");
        }
        // 规则2：参照删除，存在未放映排片场次的影片禁止下线
        if ("offline".equals(film.getStatus()) && film.getId() != null
                && scheduleService.count(QueryWrapper.create()
                        .eq("filmId", film.getId())
                        .ge("showDate", Date.valueOf(LocalDate.now()))) > 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "该影片存在未放映的排片场次，禁止下线，请先在「场次管理」中删除对应场次");
        }
    }

}
