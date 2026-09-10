package com.emotion.util;

import lombok.Data;

/**
 * 打分模型里的一个维度登记：键 + 展示名 + 权重 + 维序。
 *
 * <p>刻意做成不带 MyBatis 注解的纯值对象，和 {@code entity/ScoringDim} 分开：打分引擎
 * {@link TemperatureCalculator} 要保持静态、无 DB 依赖（为可单测 + golden 回归而刻意如此），
 * 只能吃这种 plain POJO，不能吃实体或 Mapper。Store 侧负责把实体装配成它。
 *
 * <p>{@code dimKey} 是引擎认维的唯一依据（配对时按它取分，不按位置），取值必须是
 * height/premium/breadth/broken/loss/volume/theme/anchor/surv 之一。
 */
@Data
public class DimWeight {

    private String dimKey;
    private String label;
    private double weight;
    /** 引擎维序 1..9；界面上印「第 N 维」用它，与卡片摆放顺序无关。 */
    private int dimNo;

    public DimWeight() {
    }

    public DimWeight(String dimKey, String label, double weight, int dimNo) {
        this.dimKey = dimKey;
        this.label = label;
        this.weight = weight;
        this.dimNo = dimNo;
    }
}
