package com.limou.agent.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.limou.agent.mapper.UserWantFilmMapper;
import com.limou.agent.model.entity.Film;
import com.limou.agent.model.entity.UserWantFilm;
import com.limou.agent.service.FilmService;
import com.limou.agent.service.UserWantFilmService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserWantFilmServiceImpl extends ServiceImpl<UserWantFilmMapper, UserWantFilm>
        implements UserWantFilmService {

    @Resource
    private FilmService filmService;

    @Override
    public boolean toggleWantToSee(Long userId, Long filmId) {
        // 查出任意状态的记录（包括逻辑已删除的），避免 UNIQUE 冲突
        UserWantFilm existing = this.mapper.selectAnyByUserIdAndFilmId(userId, filmId);
        if (existing != null && (existing.getIsDelete() == null || !existing.getIsDelete())) {
            // 活跃记录 → 物理删除，彻底移除
            this.mapper.physicalDeleteById(existing.getId());
            return false;
        }
        // 不存在 或 之前被逻辑删除 → 先清理旧记录，再新建
        if (existing != null) {
            this.mapper.physicalDeleteById(existing.getId());
        }
        UserWantFilm entity = UserWantFilm.builder()
                .userId(userId)
                .filmId(filmId)
                .build();
        this.save(entity);
        return true;
    }

    @Override
    public boolean isWanted(Long userId, Long filmId) {
        QueryWrapper qw = QueryWrapper.create()
                .eq("userId", userId)
                .eq("filmId", filmId);
        return this.count(qw) > 0;
    }

    @Override
    public List<Film> getMyWantToSeeFilms(Long userId) {
        QueryWrapper qw = QueryWrapper.create()
                .eq("userId", userId)
                .orderBy("createTime", false);
        List<UserWantFilm> records = this.list(qw);
        if (CollUtil.isEmpty(records)) {
            return Collections.emptyList();
        }
        List<Long> filmIds = records.stream()
                .map(UserWantFilm::getFilmId)
                .collect(Collectors.toList());
        return filmService.listByIds(filmIds);
    }

    @Override
    public void removeWantToSee(Long userId, Long filmId) {
        QueryWrapper qw = QueryWrapper.create()
                .eq("userId", userId)
                .eq("filmId", filmId);
        UserWantFilm existing = this.getOne(qw);
        if (existing != null) {
            this.removeById(existing.getId());
        }
    }

    @Override
    public long countByUserId(Long userId) {
        QueryWrapper qw = QueryWrapper.create().eq("userId", userId);
        return this.count(qw);
    }
}
