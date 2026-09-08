package com.emotion.util;

import java.math.BigDecimal;

/**
 * 人工覆盖怎么叠到自动读数上——全站只这一处，五个地方共用。
 *
 * <p>为什么单独收成一个小类：coalesce 写歪一次的后果不是"这一天错了"，是 {@code recalcAll}
 * 把全部历史日子一起改错，而这里没有 git 可回退。收成一条 {@code pick} 才谈得上被一个测试钉死。
 *
 * <p>规则只有一条：<b>{@code manual != null} 才算覆盖</b>。0 不是"没填"——
 * 0% 封板率是崩盘、0 家进分是"拉过了确实没有"，把 0 当成缺省等于把手改的崩盘读回自动值。
 */
public final class ManualOverride {

    private ManualOverride() {
    }

    public static BigDecimal pick(BigDecimal manual, BigDecimal auto) {
        return manual != null ? manual : auto;
    }

    public static Integer pick(Integer manual, Integer auto) {
        return manual != null ? manual : auto;
    }

    /** 这格到底是不是人工来的，依据串要按这个分岔写。 */
    public static boolean used(Object manual) {
        return manual != null;
    }

    /**
     * 夹回 {@code 0..DIM_MAX}。
     *
     * <p>第 8 维的人工值是唯一一个<b>直接进分子</b>的数（其余覆盖给的是百分数，还要过一遍分档）。
     * 一个 9 分能把温度顶出 100、一个 -5 能把尺子捅穿，所以宁可夹回来并说清楚，
     * 也不让一个手滑的键值把整条曲线换一把尺子。
     */
    public static Integer clampScore(Integer manual) {
        if (manual == null) {
            return null;
        }
        if (manual < 0) {
            return 0;
        }
        return manual > TemperatureCalculator.DIM_MAX ? TemperatureCalculator.DIM_MAX : manual;
    }
}
