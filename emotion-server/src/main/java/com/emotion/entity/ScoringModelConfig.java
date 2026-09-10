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
 * 打分模型登记（平台全局配置，不绑用户）。
 *
 * <p>类名叫 ScoringModelConfig 而非 ScoringModel，是为了跟打分引擎侧的纯值对象
 * {@code com.emotion.util.ScoringModel} 区分开：Store 会把本实体装配成那个值对象喂给引擎，
 * 两个类型同名又同处通配导入范围里会撞车。
 *
 * <p>{@code max_score} 与 {@code note} 标 ALWAYS：管理端 PUT 是整条替换，
 * "把分母改回按权重和现推"就是把 max_score 置 null，默认 NOT_NULL 策略会让这列从 UPDATE 里消失、
 * 清空静默失效。
 */
@Data
@TableName("t_scoring_model")
public class ScoringModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 稳定自然键（如 ultra_short）；幂等种子按它认行，改展示名不许动它。 */
    private String modelKey;
    private String name;
    /** 温度映射分母 M。NULL=按已登记权重之和×每维满分现推；写死后改权重不再自动挪尺子。<=0 管理端拒。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal maxScore;
    /** 1=当前生效。全平台只应有一行为 1。 */
    private Boolean active;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
