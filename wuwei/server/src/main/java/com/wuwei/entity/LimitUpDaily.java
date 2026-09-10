package com.wuwei.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/** 3.4 每日触板原始池。status: ZT/ZT_FIRST/BOMB/DOWN/BROKEN */
@Data
@TableName("t_limit_up_daily")
public class LimitUpDaily {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate tradeDate;
    private String tsCode;
    private String name;
    private String status;
    private Integer nZones;
    private Integer prevNZones;
    private Boolean isFirstBoard;
    private String conceptMain;
    private LocalTime firstLuTime;
    private LocalTime lastLuTime;
    private Integer openTimes;
    private Boolean isBack;
    private BigDecimal openChg;
    private BigDecimal highChg;
    private BigDecimal closeChg;
    private BigDecimal nextOpenChg;
    private BigDecimal nextCloseChg;
    private BigDecimal amount;
    private BigDecimal turnoverRate;
    private BigDecimal volYestRatio;
    private BigDecimal fdAmount;
    private BigDecimal fdFloatRatio;
    private BigDecimal maxDrawdown;
    private Boolean isBigNoodle;
    private Boolean isNuke;
    private Boolean brokenFlag;
    private String monitorStatus;
}
