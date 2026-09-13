package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.SurveillanceDaily;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 监管全生命周期每日轨迹。落库幂等：先按 (stock_code, ann_date) 删除窗口，再整体 INSERT。
 */
public interface SurveillanceDailyMapper extends BaseMapper<SurveillanceDaily> {

    /** 删除某一监管事件窗口内全部轨迹行（重算前置幂等）。 */
    @Delete("DELETE FROM t_surveillance_daily WHERE stock_code=#{code} AND ann_date=#{annDate}")
    int deleteWindow(@Param("code") String code, @Param("annDate") java.time.LocalDate annDate);

    /** 批量插入某监管事件的每日轨迹。 */
    @Insert("<script>"
            + "INSERT INTO t_surveillance_daily"
            + " (stock_code, stock_name, ann_date, kind, trade_date, day_offset,"
            + "  consecutive, change_pct, pool, big_loss, break_count, seal_amount, suspended) VALUES "
            + "<foreach collection='rows' item='d' separator=','>"
            + "(#{d.stockCode},#{d.stockName},#{d.annDate},#{d.kind},#{d.tradeDate},#{d.dayOffset},"
            + " #{d.consecutive},#{d.changePct},#{d.pool},#{d.bigLoss},#{d.breakCount},#{d.sealAmount},#{d.suspended})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("rows") List<SurveillanceDaily> rows);
}