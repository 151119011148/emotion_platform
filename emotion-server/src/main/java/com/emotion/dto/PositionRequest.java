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
    /** 手记的浮动盈亏%。留空且成本/现价都在时服务端补算，填了就用你的。 */
    private BigDecimal floatPct;
    private String action;
    private String plannedAction;
    /** 遵守 / 违约 / 待执行；留空 = 这行不参与纪律统计。 */
    private String discipline;
}
