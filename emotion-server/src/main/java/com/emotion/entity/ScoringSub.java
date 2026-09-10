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
 * 打分模型的中间层子指标登记（维→子→层 的两/三层权重与取数键；平台全局配置，不绑用户）。
 *
 * <p>一行 = 树上的一个 {@code SubNode}。{@code parentSubKey=''-'} 表示维度直属的一级子指标，
 * 非 '-' 表示挂在某个复合子指标下的层（连板·晋级·低）或叶（炸板质量·家数封板率）。
 *
 * <p>刻意 NOT NULL：与 {@code t_scoring_rule.sub_key} 同一幂等理由——MySQL UK 不约束 NULL，
 * 允许 NULL 会让"重放种子"漏一行、破坏幂等。
 *
 * <p>{@code weight} 是 0-1 小数。父节点内的兄弟权重不必恰好求和为 1，引擎按"已评子权重之和"
 * 归一化(Σw×分/Σw)；连板维 spec 明写 25/20/20/15/10=0.90 就靠这条不变量落到 0-100。
 */
@Data
@TableName("t_scoring_sub")
public class ScoringSub {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属模型，逻辑外键(t_scoring_model.id)。 */
    private Long modelId;
    /** 挂在五维里的哪一维。 */
    private String dimKey;
    /** 子指标键；连板维的四层用 promo_low / promo_mid / ... 这类独立键。 */
    private String subKey;
    /** '-'=维度直属一级子；非 '-'=挂在某复合子下的层/叶。 */
    private String parentSubKey;
    private String label;
    /** 在本父节点内的权重（0-1）。 */
    private BigDecimal weight;
    /** WEIGHTED_SUM / BAND_LADDER / LAYER_WEIGHTED_BAND / STRATEGY / MANUAL。 */
    private String scoringKind;
    /** BAND_LADDER/MANUAL 取 metrics[sourceKey]；STRATEGY 存策略名；复合类 NULL。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceKey;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer sortNo;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
