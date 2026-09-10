package com.wuwei.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/** 3.5 连板派生表。tier: LOW/MID/MIDHIGH/HIGH 四层 */
@Data
@TableName("t_lianban_daily")
public class LianbanDaily {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate tradeDate;
    private String tsCode;
    private String name;
    private Integer nZones;
    private String tier;
    private String conceptMain;
    private Integer prevNZones;
    private Integer jrBaseCount;
    private Boolean isPromote;
    private LocalTime firstLuTime;
    private Integer openTimes;
    private Boolean isBack;
    private BigDecimal fdAmount;
    private BigDecimal turnoverRate;
    private BigDecimal volYestRatio;
    private BigDecimal openChg;
    private BigDecimal closeChg;
    private BigDecimal maxDrawdown;
    private Boolean isBigNoodle;
    private Boolean isNuke;
    private String monitorStatus;
    /** 是否空间板（总龙头候选） */
    private Boolean isSpaceLeader;
    /** 是否板块龙头（中军候选） */
    private Boolean isSectorLeader;
    /** 龙头行为：PROMOTE/HOLD/BREAK/NUKE */
    private String leaderAction;
}
