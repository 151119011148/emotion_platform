package com.emotion.vo;

import java.math.BigDecimal;
import java.util.List;

import com.emotion.entity.ScoringDim;
import com.emotion.entity.ScoringModelConfig;
import com.emotion.entity.ScoringRule;
import com.emotion.entity.ScoringSub;

import lombok.Data;

/**
 * 一个打分模型的完整视图：模型本体 + 维度 + 计算规则 + 派生量。
 *
 * <p>既是管理页的读模型（列模型/维/规则、编辑回填），也是 {@code /effective} 端点给前端卡片的
 * 维度来源。{@code source}=DB 表示库里确实装配出了生效模型；BUILTIN 表示库里没生效模型或读取出错，
 * 引擎侧回退内置默认，前端侧回退 scores.js 常量。
 */
@Data
public class ScoringModelVO {

    /** 生效/选中的模型本体；BUILTIN 时为内置默认的展示壳（id 为空、维度权重来自引擎常量）。 */
    private ScoringModelConfig model;
    private List<ScoringDim> dims;
    /** 五维双层模型的中间层：子指标 / 四层，按 (dim_key,parent_sub_key,sort_no) 排好。旧 9 维模型为空。 */
    private List<ScoringSub> subs;
    private List<ScoringRule> rules;
    /** DB=库里装配出的生效模型；BUILTIN=回退内置默认。 */
    private String source;
    /** 温度映射真正用的分母 M：max_score 写死就用它，否则=权重和×每维满分(3)。 */
    private BigDecimal effectiveMaxScore;
    /** 各维权重合计，管理页页脚与温度式实时显示用。 */
    private BigDecimal weightSum;
}
