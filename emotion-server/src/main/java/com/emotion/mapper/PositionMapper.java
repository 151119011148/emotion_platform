package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.Position;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PositionMapper extends BaseMapper<Position> {

    /** 一天一般一两行，但仍走多行 VALUES：导入一次要写四张表，逐条往返没必要。 */
    @Insert("<script>"
            + "INSERT INTO t_position (user_id, trade_date, stock_code, stock_name, cost_price, "
            + "current_price, float_pct, action, planned_action, discipline) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.userId},#{r.tradeDate},#{r.stockCode},#{r.stockName},#{r.costPrice},"
            + "#{r.currentPrice},#{r.floatPct},#{r.action},#{r.plannedAction},#{r.discipline})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<Position> rows);
}
