package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日持仓与纪律台账：复盘 md 里一条 {@code 持仓:} 一行。
 *
 * <p>绑 user_id——这是你的账，不是公开行情。拆成行而不是塞进 review_note，
 * 是为了能问"同一只票连续第几次应做未做"，那句话是这份笔记最该沉淀的东西。
 */
@Data
@TableName("t_position")
public class Position {

    /** 按纪律做了。 */
    public static final String DISCIPLINE_KEPT = "遵守";
    /** 该做没做——这块面板存在的理由。 */
    public static final String DISCIPLINE_BROKEN = "违约";
    /** 还没到该做的时点（如"次日竞价止损"落在当天盘前）。 */
    public static final String DISCIPLINE_PENDING = "待执行";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate tradeDate;
    private String stockCode;
    /** 以 t_stock 反查为准，不采信 md 里写的那个名字。 */
    private String stockName;
    private BigDecimal costPrice;
    private BigDecimal currentPrice;
    /**
     * 持仓股数（手记）。只有单价没有股数时「总成本」只能把不同股票的股价相加，
     * 数学上不成立；有它才算得出成本额 = Σ(股数×成本)、市值 = Σ(股数×现价)。
     * NULL = 没填，汇总按无股数回退到等权口径，<b>不要用 0 冒充</b>——
     * 0 是「一股没买」，和「不知道买了多少」是两回事。
     */
    private Integer quantity;
    /** 手记的浮动盈亏%，原样存；不由成本/现价反推——你记的可能是含费后的数。 */
    private BigDecimal floatPct;
    private String action;
    private String plannedAction;
    private String discipline;
    /** 所属板块/D2 核心板块（手填或自动）。 */
    private String industry;
    /** 买入时板数。 */
    private Integer boardNum;
    /** 持仓中 / 今日清仓（三段式①/② 分组）。 */
    private String status;
    /** 清仓延迟天数（应做未及时做）。 */
    private Integer delayDays;
    /** 纪律评分 0-100，违规按延迟折减。 */
    private Integer disciplineScore;
    /** 次日处理决策（竞价裁决）。 */
    private String nextDayPlan;
    /** 次日高开→动作（分档①，外溢用）。 */
    private String planOpen;
    /** 次日炸板→动作（分档②，外溢用）。 */
    private String planBreak;
    /** 次日平开/低开→动作（分档③，外溢用）。 */
    private String planLow;
    /** 次日跌停→动作（分档④，外溢用）。 */
    private String planFall;
    /** 外溢闭环：0=待裁决，1=已在次日复盘页标记执行。 */
    private Integer executed;
    /** 标记执行时回填的真实动作。 */
    private String actualAction;
    private LocalDateTime createdAt;
}
