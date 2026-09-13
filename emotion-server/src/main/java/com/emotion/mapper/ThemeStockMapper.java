package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.ThemeStock;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface ThemeStockMapper extends BaseMapper<ThemeStock> {

    /** 回填是幂等重放：先删该用户该日所有 AUTO 行，再整批插入，避免残留过期的自动归类。 */
    @Delete("DELETE FROM t_theme_stock WHERE user_id=#{userId} AND trade_date=#{date} AND source='AUTO'")
    int deleteAutoForDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    /** 该用户该日 AUTO 回填行数：==0 说明历史日期还没回填过，一次补上；>0 则跳过避免反复写历史。 */
    @Select("SELECT COUNT(*) FROM t_theme_stock WHERE user_id=#{userId} AND trade_date=#{date} AND source='AUTO'")
    long countAuto(@Param("userId") Long userId, @Param("date") LocalDate date);

    @Insert("<script>"
            + "INSERT INTO t_theme_stock (user_id, theme_id, trade_date, code, name, industry, is_primary, source) "
            + "VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.userId},#{r.themeId},#{r.tradeDate},#{r.code},#{r.name},#{r.industry},#{r.isPrimary},#{r.source})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<ThemeStock> rows);
}