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
    /** 手记的浮动盈亏%，原样存；不由成本/现价反推——你记的可能是含费后的数。 */
    private BigDecimal floatPct;
    private String action;
    private String plannedAction;
    private String discipline;
    private LocalDateTime createdAt;
}
