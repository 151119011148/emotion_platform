package com.emotion.util;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 一条阈值档位（0-100 打分阶梯里的一档）。对应 t_scoring_rule 的一行（BAND_LADDER 子）。
 *
 * operator 只在这四类里参与打分求值：GTE/GT/LTE/LT/EQ/BETWEEN，收尾用 ELSE。
 * COMPOUND/GUARD/AGG 是策略/结构行，打分引擎不读（策略算法在 Java，行只供界面展示与 Parity 追溯）。
 */
@Data
public class BandRule {

    private String operator;
    private BigDecimal thresholdLow;
    private BigDecimal thresholdHigh;
    private BigDecimal score;

    public BandRule() {
    }

    public BandRule(String operator, BigDecimal thresholdLow, BigDecimal thresholdHigh, BigDecimal score) {
        this.operator = operator;
        this.thresholdLow = thresholdLow;
        this.thresholdHigh = thresholdHigh;
        this.score = score;
    }

    /** 便于用 double 字面量搭内置树。 */
    public static BandRule of(String operator, Double low, Double high, double score) {
        return new BandRule(operator,
                low == null ? null : BigDecimal.valueOf(low),
                high == null ? null : BigDecimal.valueOf(high),
                BigDecimal.valueOf(score));
    }
}
