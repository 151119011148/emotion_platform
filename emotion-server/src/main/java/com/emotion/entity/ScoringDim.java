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
 * 打分模型的维度登记：维集合与权重（平台全局配置，不绑用户）。
 *
 * <p>这一张表就是"注册表驱动"的那半个注册表——增删一维、调一维权重都是改数据不改 Java。
 * 引擎按 {@code dim_key} 取权重配对（不看 {@code dim_no} 顺序），所以 {@code dim_no} 写错只会印错
 * "第 N 维"这个标号，不会把权重串到别的维上。
 *
 * <p>{@code sort_no}/{@code recordColumn}/{@code note} 允许置回 NULL，故标 ALWAYS。
 */
@Data
@TableName("t_scoring_dim")
public class ScoringDim {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属模型，逻辑外键(t_scoring_model.id)；级联删除在服务层做。 */
    private Long modelId;
    /** 维度键，必须属于 height/premium/breadth/broken/loss/volume/theme/anchor/surv；引擎只认这九个，写错=静默少一维，故服务层硬校验。 */
    private String dimKey;
    /** 引擎维序 1..9，界面印"第 N 维"用它；与卡片摆放顺序无关（那是 sort_no）。 */
    private Integer dimNo;
    /** 卡面中文名。 */
    private String label;
    /** 权重，进分子的是 该维分×weight。 */
    private BigDecimal weight;
    /** 卡片展示序（成交额打头那串）。NULL=不上卡片。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer sortNo;
    /** 该维的分落在 t_daily_record 哪一列，仅展示/追溯；第9维无独立分列故 NULL，第8维是 anchor_score。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String recordColumn;
    /** 合成方式：THRESHOLD_BAND / WEIGHTED_SUB_BANDS / SUBITEM_AVERAGE / WORST_OF_MANY / MANUAL_PASSTHROUGH。算法体在 Java，本列只标类别。 */
    private String ruleEngine;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
