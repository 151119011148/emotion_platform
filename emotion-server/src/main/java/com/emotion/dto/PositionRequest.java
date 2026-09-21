package com.emotion.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 持仓台账的一行，{@code PUT /api/records/positions} 的 body 元素。
 *
 * <p>字段与 {@code 持仓:} 那条 md 键一一对应——两条路写同一张表、同一个
 * {@code PositionStore.replaceForDate}，字段不同就没法解释"为什么导入页写的和复盘页写的不是一个东西"。
 */
@Data
public class PositionRequest {
    private String stockCode;
    /** 只是候选：最终以 t_stock 反查到的正名为准，与 md 导入同一个规矩。 */
    private String stockName;
    private BigDecimal costPrice;
    private BigDecimal currentPrice;
    /** 持仓股数；留空 = 没填，该行不进金额类汇总（市值/盈亏额），只进等权口径。 */
    private Integer quantity;
    /** 手记的浮动盈亏%。留空且成本/现价都在时服务端补算，填了就用你的。 */
    private BigDecimal floatPct;
    private String action;
    private String plannedAction;
    /** 遵守 / 违约 / 待执行；留空 = 这行不参与纪律统计。 */
    private String discipline;
    /** 所属板块/D2 核心板块。 */
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
    /** 次日高开→动作（分档①）。 */
    private String planOpen;
    /** 次日炸板→动作（分档②）。 */
    private String planBreak;
    /** 次日平开/低开→动作（分档③）。 */
    private String planLow;
    /** 次日跌停→动作（分档④）。 */
    private String planFall;
    /** 外溢闭环：0=待裁决，1=已标记执行。 */
    private Integer executed;
    /** 标记执行时回填的真实动作。 */
    private String actualAction;
}
