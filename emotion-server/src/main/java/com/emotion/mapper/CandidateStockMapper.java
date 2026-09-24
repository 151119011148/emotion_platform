package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.CandidateStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/** 候选池明细。 */
public interface CandidateStockMapper extends BaseMapper<CandidateStock> {

    /**
     * 某策略某日的候选，按展示顺序返回。
     *
     * <p>取的是最新一次运行（{@code run_id} 最大）——重跑会整日替换，理论上同一天只有一批，
     * 用 MAX(run_id) 是为了兜住「删旧插新」中途失败的极端情况，宁可少给也不要给出两批打架的清单。
     */
    @Select("SELECT * FROM t_candidate_stock WHERE strategy_id = #{strategyId} AND trade_date = #{date} "
            + "AND run_id = (SELECT MAX(run_id) FROM t_candidate_stock "
            + "              WHERE strategy_id = #{strategyId} AND trade_date = #{date}) "
            + "ORDER BY rank_no")
    List<CandidateStock> listOfDay(@Param("strategyId") Long strategyId, @Param("date") LocalDate date);

    /** 某日候选的代码集合，补写 D+1 表现时用它确定要验哪几只。 */
    @Select("SELECT code FROM t_candidate_stock WHERE strategy_id = #{strategyId} AND trade_date = #{date} "
            + "AND run_id = (SELECT MAX(run_id) FROM t_candidate_stock "
            + "              WHERE strategy_id = #{strategyId} AND trade_date = #{date})")
    List<String> listCodesOfDay(@Param("strategyId") Long strategyId, @Param("date") LocalDate date);
}
