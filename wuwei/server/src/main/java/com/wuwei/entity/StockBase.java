package com.wuwei.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 3.1 股票主池 */
@Data
@TableName("t_stock_base")
public class StockBase {
    @TableId(type = IdType.INPUT)
    private String tsCode;
    private String symbol;
    private String name;
    private String exchange;
    private String board;
    private String industry;
    private Boolean isSt;
    private Boolean isNewStock;
    private LocalDate listDate;
    private BigDecimal freeFloat;
    private BigDecimal totalFloat;
    private BigDecimal limitPct;
    private Boolean delisted;
    private LocalDateTime updatedAt;
}
