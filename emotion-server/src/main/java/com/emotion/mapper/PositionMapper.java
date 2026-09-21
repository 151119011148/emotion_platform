package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.Position;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PositionMapper extends BaseMapper<Position> {

    /**
     * 一天一般一两行，但仍走多行 VALUES：导入一次要写四张表，逐条往返没必要。
     * 列清单必须与 {@link com.emotion.service.ReviewLedgerService#savePositions} 设置的字段一一对齐——
     * 曾经只列基础十列，status/纪律分/次日决策等三段式字段在 Entity 上设了值却在落库时被静默丢弃，
     * 台账页读到的永远是默认值；改列清单时两边要一起动。
     */
    @Insert("<script>"
            + "INSERT INTO t_position (user_id, trade_date, stock_code, stock_name, cost_price, "
            + "current_price, float_pct, action, planned_action, discipline, industry, board_num, "
            + "status, delay_days, discipline_score, next_day_plan, plan_open, plan_break, "
            + "plan_low, plan_fall, executed, actual_action) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.userId},#{r.tradeDate},#{r.stockCode},#{r.stockName},#{r.costPrice},"
            + "#{r.currentPrice},#{r.floatPct},#{r.action},#{r.plannedAction},#{r.discipline},"
            + "#{r.industry},#{r.boardNum},#{r.status},#{r.delayDays},#{r.disciplineScore},"
            + "#{r.nextDayPlan},#{r.planOpen},#{r.planBreak},#{r.planLow},#{r.planFall},"
            + "#{r.executed},#{r.actualAction})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<Position> rows);
}
