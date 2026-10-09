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

    /** 回填是幂等重放：先删该日所有 AUTO 行，再整批插入，避免残留过期的自动归类。 */
    @Delete("DELETE FROM t_theme_stock WHERE trade_date=#{date} AND source='AUTO'")
    int deleteAutoForDate(@Param("date") LocalDate date);

    /** 该日 AUTO 回填行数：==0 说明历史日期还没回填过，一次补上；>0 则跳过避免反复写历史。 */
    @Select("SELECT COUNT(*) FROM t_theme_stock WHERE trade_date=#{date} AND source='AUTO'")
    long countAuto(@Param("date") LocalDate date);

    /** 整批绑定走 INSERT IGNORE：并发重建同一日时 AUTO 行内容完全一致，撞 uk_theme_date_code 忽略掉就是正确的合并结果。 */
    @Insert("<script>"
            + "INSERT IGNORE INTO t_theme_stock (theme_id, trade_date, code, name, industry, is_primary, source) "
            + "VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.themeId},#{r.tradeDate},#{r.code},#{r.name},#{r.industry},#{r.isPrimary},#{r.source})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<ThemeStock> rows);
}