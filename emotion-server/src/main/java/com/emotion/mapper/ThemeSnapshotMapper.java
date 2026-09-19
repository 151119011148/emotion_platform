package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.ThemeSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code t_theme_daily_snapshot} 的读写口。写侧只有整批落盘（先删后插由 Service 承担），
 * 一行一题材多行 VALUES 一次写,与 {@code t_industry_daily_snapshot} 同一族约定。
 */
public interface ThemeSnapshotMapper extends BaseMapper<ThemeSnapshot> {

    @Insert("<script>"
            + "INSERT INTO t_theme_daily_snapshot "
            + "(trade_date, user_id, `rank`, theme_name, zt_count, strength, max_board, "
            + " continuous_days, hardness, lifecycle, related_industries, leader_code, leader_name, leader_board) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.tradeDate},#{r.userId},#{r.rank},#{r.themeName},#{r.ztCount},#{r.strength},#{r.maxBoard},"
            + " #{r.continuousDays},#{r.hardness},#{r.lifecycle},#{r.relatedIndustries},#{r.leaderCode},#{r.leaderName},#{r.leaderBoard})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<ThemeSnapshot> rows);
}