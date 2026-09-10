package com.emotion.util;

import java.util.List;

import lombok.Data;

/**
 * 五维模型里的一维（t_scoring_dim 的一行 + 它名下 parent='-' 的一级子指标）。
 * 维本身一律 WEIGHTED_SUM：维分 = Σ 子权重×子分（子分 0-100），未评子指标剔出分母。
 */
@Data
public class DimNode {

    private String dimKey;
    private String label;
    private double weight;
    private int dimNo;
    private String recordColumn;
    private List<SubNode> subs;

    public DimNode() {
    }

    public DimNode(String dimKey, String label, double weight, int dimNo, String recordColumn, List<SubNode> subs) {
        this.dimKey = dimKey;
        this.label = label;
        this.weight = weight;
        this.dimNo = dimNo;
        this.recordColumn = recordColumn;
        this.subs = subs;
    }
}
