package com.limou.agent.service;

import com.limou.agent.model.entity.Film;
import com.limou.agent.model.entity.UserWatchedFilm;
import com.mybatisflex.core.service.IService;

import java.util.List;

public interface UserWatchedFilmService extends IService<UserWatchedFilm> {

    void markAsWatched(Long userId, Long filmId);

    /** 切换看过状态：已看过→取消，未看过→标记。返回 true=已看过，false=已取消 */
    boolean toggleWatched(Long userId, Long filmId);

    boolean isWatched(Long userId, Long filmId);

    List<Film> getMyWatchedFilms(Long userId);

    long countByUserId(Long userId);
}
