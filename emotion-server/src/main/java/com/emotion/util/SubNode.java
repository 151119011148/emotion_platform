package com.emotion.util;

import java.util.List;

import lombok.Data;

/**
 * 打分树里的一个"子指标"节点（t_scoring_sub 的一行）。既当一级子指标（parent='-'，挂在维下），
 * 也当复合子指标下面的层/叶（如连板·晋级·低）。整棵树就是 DimNode → SubNode（可递归 children）→ BandRule。
 *
 * 求值方式由 scoringKind 决定：
 *  - BAND_LADDER：读 metrics[sourceKey] 走 ladder 有序命中，缺值=该叶未评（null）。
 *  - STRATEGY：调 Java 命名算法（sourceKey 即策略名，如 index_env/limit_combo/board_anchor）。
 *  - MANUAL：直接取 metrics[sourceKey] 夹到 0-100，缺值未评。
 *  - WEIGHTED_SUM / LAYER_WEIGHTED_BAND：本节点不出分，按 children 的 weight 加权合成（未评 child 剔出分母）。
 */
@Data
public class SubNode {

    private String subKey;
    private String label;
    private double weight;
    private String scoringKind;
    private String sourceKey;
    private List<SubNode> children;
    private List<BandRule> ladder;

    public SubNode() {
    }

    public SubNode(String subKey, String label, double weight, String scoringKind, String sourceKey) {
        this.subKey = subKey;
        this.label = label;
        this.weight = weight;
        this.scoringKind = scoringKind;
        this.sourceKey = sourceKey;
    }

    /** 叶子：BAND_LADDER（带阶梯）。 */
    public static SubNode band(String subKey, String label, double weight, String sourceKey, List<BandRule> ladder) {
        SubNode n = new SubNode(subKey, label, weight, "BAND_LADDER", sourceKey);
        n.setLadder(ladder);
        return n;
    }

    /** 叶子：STRATEGY（算法在 Java）。 */
    public static SubNode strategy(String subKey, String label, double weight, String strategyName) {
        return new SubNode(subKey, label, weight, "STRATEGY", strategyName);
    }

    /** 叶子：MANUAL（人工直接给 0-100）。 */
    public static SubNode manual(String subKey, String label, double weight, String sourceKey) {
        return new SubNode(subKey, label, weight, "MANUAL", sourceKey);
    }

    /** 复合节点：按 children 权重加权合成。 */
    public static SubNode composite(String subKey, String label, double weight, String scoringKind, List<SubNode> children) {
        SubNode n = new SubNode(subKey, label, weight, scoringKind, null);
        n.setChildren(children);
        return n;
    }
}
