package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A股休市停机表：工作日上的官方休市日，交易日历过滤用。 */
@Data
@TableName("t_trading_holidays")
public class TradingHoliday implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate tradeDate;

    private String reason;

    private LocalDateTime createdAt;
}