package com.limou.agent.mapper;

import com.limou.agent.model.entity.UserWatchedFilm;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserWatchedFilmMapper extends BaseMapper<UserWatchedFilm> {

    @Select("SELECT * FROM user_watched_film WHERE userId = #{userId} AND filmId = #{filmId}")
    UserWatchedFilm selectAnyByUserIdAndFilmId(@Param("userId") Long userId, @Param("filmId") Long filmId);

    @Delete("DELETE FROM user_watched_film WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);
}
