package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.FirstBoardScreen;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code t_first_board_screen} 的读写口。写侧只有 15:30 选股链路一个调用方，
 * 读侧是 D6 首板生态与 D9 候选池。
 */
public interface FirstBoardScreenMapper extends BaseMapper<FirstBoardScreen> {

    /** 整日替换的第一步：当天重算就先把当天旧行清掉，一个交易日对一只票只该有一份判定。 */
    @Delete("DELETE FROM t_first_board_screen WHERE trade_date = #{date}")
    int deleteByDate(@Param("date") LocalDate date);

    /** 整日写入：一票一行，多行 VALUES 一次写一批（一只票一次判定最多几十行，不必再分片）。 */
    @Insert("<script>"
            + "INSERT INTO t_first_board_screen (trade_date, code, name, industry, concepts, concept_count, "
            + "  ladder_concepts, overlap_count, close_price, ma250, above_pct, bars_used, passed, reason) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.tradeDate},#{r.code},#{r.name},#{r.industry},#{r.concepts},#{r.conceptCount},"
            + " #{r.ladderConcepts},#{r.overlapCount},#{r.closePrice},#{r.ma250},#{r.abovePct},#{r.barsUsed},"
            + " #{r.passed},#{r.reason})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<FirstBoardScreen> rows);

    /** 当日全部判定（含未通过的），按代码升序，两次刷新顺序稳定。 */
    @Select("SELECT * FROM t_first_board_screen WHERE trade_date = #{date} ORDER BY passed DESC, code")
    List<FirstBoardScreen> listByDate(@Param("date") LocalDate date);

    /** 当日通过两条判据的首板——D9 候选池的唯一取数口径。 */
    @Select("SELECT * FROM t_first_board_screen WHERE trade_date = #{date} AND passed = 1 ORDER BY above_pct DESC, code")
    List<FirstBoardScreen> listPassedByDate(@Param("date") LocalDate date);
}
