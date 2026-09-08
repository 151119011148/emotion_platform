package com.emotion.vo;

import lombok.Data;
import java.util.List;

@Data
public class TemperatureCurveVO {
    private List<String> dates;
    /**
     * 与 dates 同序的 ISO 日期（2026-09-04）。dates 是轴上给人看的 MM/dd，
     * 拿它去对阵眼跨度的 ISO 起点就得反推年份——跨年时一定会对错位。
     * 这一列只为对齐存在，界面显示仍然用 dates。
     */
    private List<String> isoDates;
    /** 当日温度；一维都没评出来时为 null（不是 0——画成 0° 会被读成"冰点"）。 */
    private List<Double> temperatures;
    private List<String> stages;
    /**
     * 与 dates 等长的子段标签（{@code 退潮 · 一阶段}），缺维或没算过段号时为 ""。
     * 主阶段还是 stages 那个七个之一的口径不变，这里只补"第几回合"。
     */
    private List<String> labels;
    /** 与 dates 等长的盘面摘要，供 tooltip 直接展示。 */
    private List<String> summaries;
    /** 与 dates 等长的实际参与打分维数（0-9），少于 5 维不出阶段。 */
    private List<Integer> dims;
}
