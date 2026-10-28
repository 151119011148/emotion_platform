package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.Position;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
            + "current_price, quantity, float_pct, action, planned_action, discipline, industry, board_num, "
            + "status, delay_days, discipline_score, next_day_plan, plan_open, plan_break, "
            + "plan_low, plan_fall, executed, actual_action, sell_price, sell_qty) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.userId},#{r.tradeDate},#{r.stockCode},#{r.stockName},#{r.costPrice},"
            + "#{r.currentPrice},#{r.quantity},#{r.floatPct},#{r.action},#{r.plannedAction},#{r.discipline},"
            + "#{r.industry},#{r.boardNum},#{r.status},#{r.delayDays},#{r.disciplineScore},"
            + "#{r.nextDayPlan},#{r.planOpen},#{r.planBreak},#{r.planLow},#{r.planFall},"
            + "#{r.executed},#{r.actualAction},#{r.sellPrice},#{r.sellQty})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<Position> rows);

    /**
     * 最后一个记账日里状态仍为「持仓中」的标的，供盘前任务定名单。
     *
     * <p>取「最近一天在账上」而不是「最近 N 天在账上」：清仓是逐日改 status 的，
     * 更早的持仓行几乎都已被后面的行替掉，拉宽窗口只会把已经出掉的票重新请回来。
     */
    @Select("SELECT DISTINCT stock_code FROM t_position "
            + "WHERE trade_date = (SELECT MAX(trade_date) FROM t_position) AND status = '持仓中'")
    List<String> listHeldCodesLatest();
}
