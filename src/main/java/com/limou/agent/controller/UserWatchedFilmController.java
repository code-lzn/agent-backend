package com.limou.agent.controller;

import com.limou.agent.common.BaseResponse;
import com.limou.agent.common.ResultUtils;
import com.limou.agent.model.entity.Film;
import com.limou.agent.model.entity.User;
import com.limou.agent.service.UserService;
import com.limou.agent.service.UserWatchedFilmService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/userWatchedFilm")
public class UserWatchedFilmController {

    @Resource
    private UserWatchedFilmService userWatchedFilmService;

    @Resource
    private UserService userService;

    private Long getLoginUserId(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return loginUser.getId();
    }

    @PostMapping("/mark/{filmId}")
    public BaseResponse<Void> markAsWatched(@PathVariable Long filmId,
                                             HttpServletRequest request) {
        Long userId = getLoginUserId(request);
        userWatchedFilmService.markAsWatched(userId, filmId);
        return ResultUtils.success(null);
    }

    @PostMapping("/toggle/{filmId}")
    public BaseResponse<Map<String, Object>> toggleWatched(@PathVariable Long filmId,
                                                            HttpServletRequest request) {
        Long userId = getLoginUserId(request);
        boolean watched = userWatchedFilmService.toggleWatched(userId, filmId);
        Map<String, Object> result = new HashMap<>();
        result.put("watched", watched);
        result.put("filmId", filmId);
        return ResultUtils.success(result);
    }

    @GetMapping("/isWatched/{filmId}")
    public BaseResponse<Map<String, Object>> isWatched(@PathVariable Long filmId,
                                                        HttpServletRequest request) {
        Long userId = getLoginUserId(request);
        boolean watched = userWatchedFilmService.isWatched(userId, filmId);
        Map<String, Object> result = new HashMap<>();
        result.put("watched", watched);
        return ResultUtils.success(result);
    }

    @GetMapping("/my")
    public BaseResponse<List<Film>> getMyWatched(HttpServletRequest request) {
        Long userId = getLoginUserId(request);
        List<Film> films = userWatchedFilmService.getMyWatchedFilms(userId);
        return ResultUtils.success(films);
    }

    @GetMapping("/count")
    public BaseResponse<Map<String, Object>> count(HttpServletRequest request) {
        Long userId = getLoginUserId(request);
        long count = userWatchedFilmService.countByUserId(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("count", count);
        return ResultUtils.success(result);
    }
}
