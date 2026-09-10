package com.emotion.util;

import java.util.List;

import lombok.Data;

/**
 * 一次打分要用的模型快照：生效模型的维度集合、权重与温度映射分母。
 *
 * <p>由 {@code ScoringModelStore} 从 t_scoring_model / t_scoring_dim 装配，经
 * {@link ScoreInputs#getScoringModel()} 传进 {@link TemperatureCalculator}。为 null 时引擎
 * 退回内置默认（{@code builtinModel()}），保证单测与"配置读不到"都能算出与今天一致的分。
 *
 * <p>{@code dims} 应为不可变列表（Store 装配完包 unmodifiableList 再进缓存），防止某个调用方
 * 就地改缓存里的权重污染后面所有打分。
 */
@Data
public class ScoringModel {

    private String modelKey;
    private String name;
    /** 有序（按 dim_no）；引擎按 dimKey 取权重配对，顺序不影响结果。 */
    private List<DimWeight> dims;
    /** 温度映射分母 M：温度=(加权和+M)/(2M)*100。null=按本模型权重之和×每维满分现推。 */
    private Double maxScore;
}
