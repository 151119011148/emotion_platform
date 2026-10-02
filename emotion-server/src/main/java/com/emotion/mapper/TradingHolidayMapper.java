package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.TradingHoliday;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/** 交易日历休市停机表。 */
public interface TradingHolidayMapper extends BaseMapper<TradingHoliday> {

    /** [from,to] 区间内落在工作日的官方休市日，升序。 */
    @Select("SELECT trade_date FROM t_trading_holidays "
            + "WHERE trade_date >= #{from} AND trade_date <= #{to} ORDER BY trade_date")
    List<LocalDate> listBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 整张表，升序。给日期面板用：未来段的休市日也要知道，不能只到今天为止。 */
    @Select("SELECT trade_date FROM t_trading_holidays ORDER BY trade_date")
    List<LocalDate> listAll();
}