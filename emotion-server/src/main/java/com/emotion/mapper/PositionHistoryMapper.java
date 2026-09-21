package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.PositionHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PositionHistoryMapper extends BaseMapper<PositionHistory> {

    @Insert("<script>"
            + "INSERT INTO t_position_history (user_id, trade_date, snapshot_date, position_id, "
            + "stock_code, stock_name, cost_price, current_price, quantity, float_pct, action, planned_action, "
            + "discipline, industry, board_num, status, delay_days, discipline_score, "
            + "next_day_plan, plan_open, plan_break, plan_low, plan_fall) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.userId},#{r.tradeDate},#{r.snapshotDate},#{r.positionId},"
            + "#{r.stockCode},#{r.stockName},#{r.costPrice},#{r.currentPrice},#{r.quantity},#{r.floatPct},#{r.action},"
            + "#{r.plannedAction},#{r.discipline},#{r.industry},#{r.boardNum},#{r.status},#{r.delayDays},"
            + "#{r.disciplineScore},#{r.nextDayPlan},#{r.planOpen},#{r.planBreak},#{r.planLow},#{r.planFall})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<PositionHistory> rows);
}