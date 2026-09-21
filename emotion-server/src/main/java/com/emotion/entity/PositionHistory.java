package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 持仓台账整表替换前的自动快照（t_position_history）。
 *
 * <p>同一行当天被覆写多次会留下多条快照；字段与 {@link Position} 对齐，回滚时原样restore即可。
 * 它是"整表替换"这个原语的安全网：手滑保存导致覆盖当天已录持仓时，能从这里捞回来。
 */
@Data
@TableName("t_position_history")
public class PositionHistory {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 被替换的交易日。 */
    private LocalDate tradeDate;
    /** 快照发生日。 */
    private LocalDate snapshotDate;
    /** 对应 t_position.id（当天被删行已删，仅作溯源）。 */
    private Long positionId;
    private String stockCode;
    private String stockName;
    private BigDecimal costPrice;
    private BigDecimal currentPrice;
    /** 被替换时的持仓股数：少了它，一次保存就把股数丢了、回滚也捞不回来。 */
    private Integer quantity;
    private BigDecimal floatPct;
    private String action;
    private String plannedAction;
    private String discipline;
    private String industry;
    private Integer boardNum;
    private String status;
    private Integer delayDays;
    private Integer disciplineScore;
    private String nextDayPlan;
    private String planOpen;
    private String planBreak;
    private String planLow;
    private String planFall;
    private LocalDateTime createdAt;
}