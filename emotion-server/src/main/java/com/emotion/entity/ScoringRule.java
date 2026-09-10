package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 各维阈值档位登记（展示与追溯用，打分引擎不读）。
 *
 * <p>真源是 {@code TemperatureCalculator} 的 if 阶梯；这张表把那阶梯摊成一行行数据，
 * 让复盘的人能对着卡片核"这个分是按哪条判据给的"，也让管理端能改展示名/备注。
 * 引擎不会从这些行反推分数——改这里不影响算分。
 *
 * <p>多数可空列（threshold/score/formula/note）标 ALWAYS：PUT 整条替换时，
 * "把 BETWEEN 改回单边 GTE"要把上界清空、"GUARD 行不出分"要 score=null，
 * 默认 NOT_NULL 会让这些置空静默失效。
 */
@Data
@TableName("t_scoring_rule")
public class ScoringRule {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属模型，逻辑外键(t_scoring_model.id)。 */
    private Long modelId;
    /** 挂在哪一维。用 dim_key 不用 dim_id：删建同维会换 id，按 id 挂会把规则孤儿掉。 */
    private String dimKey;
    /** 子档键：第2维 LOW/MID/HIGH，第4维 BROKEN_RATE/SEALED_HOME/RESEAL，单套阶梯用哨兵 "-"。NOT NULL 是为幂等（UK 不约束 NULL）。 */
    private String subKey;
    /** 命中顺序，小的先判。引擎 if 阶梯有序，乱序会算出别的分，故这一列是语义不是排版。 */
    private Integer ruleNo;
    /** GTE/GT/LTE/LT/EQ/BETWEEN/ELSE + GUARD/GROUP/AGG/COMPOUND 结构行。 */
    private String operator;
    /** 阈值下界（BETWEEN 下界；GROUP 行放子档权重）。单位随所属维走。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal thresholdLow;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal thresholdHigh;
    /** 命中给分 -3~3。GUARD/GROUP/AGG 不出分故允许 NULL。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer score;
    /** 引擎不读；跨操作数 OR 等压不进三元组的判据用人话记在这里。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String formula;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
