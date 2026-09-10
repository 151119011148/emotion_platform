package com.emotion.util;

import java.util.List;

import lombok.Data;

/**
 * 五维双层打分配置树的快照（对应 t_scoring_model + 其 dims→subs→layers→ladders 全量）。
 *
 * 由 ScoreContextService 从 ScoringModelStore 装配后注入 ScoreInputs，交给
 * {@code BoardScoreCalculator} 这个纯函数消费——引擎自身不碰 DB，保证可单测、可 golden 回归。
 * maxScore 是总分满刻度（五维模型=100，总分=Σ维分×维权 直接落 0..100，不再做 (x+M)/2M 映射）。
 */
@Data
public class ScoringTree {

    private String modelKey;
    private String name;
    private double maxScore;
    private List<DimNode> dims;

    public ScoringTree() {
    }

    public ScoringTree(String modelKey, String name, double maxScore, List<DimNode> dims) {
        this.modelKey = modelKey;
        this.name = name;
        this.maxScore = maxScore;
        this.dims = dims;
    }
}
