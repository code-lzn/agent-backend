package com.limou.agent.mapper;

import com.limou.agent.model.entity.UserWantFilm;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserWantFilmMapper extends BaseMapper<UserWantFilm> {

    @Select("SELECT * FROM user_want_film WHERE userId = #{userId} AND filmId = #{filmId}")
    UserWantFilm selectAnyByUserIdAndFilmId(@Param("userId") Long userId, @Param("filmId") Long filmId);

    /** 物理删除，绕过逻辑删除机制 */
    @Delete("DELETE FROM user_want_film WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);
}
