package com.emotion.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 监管全生命周期轨迹接口返回：主视图（热力表 items）+ 辅视图（≤5 只时的累计涨幅曲线）。
 */
@Data
public class SurveillanceTrackVO {

    private LocalDate date;
    private List<Item> items = new ArrayList<>();
    private List<Series> chart = new ArrayList<>();

    /** 一只监控股的监管期轨迹。 */
    @Data
    public static class Item {
        private String code;
        private String name;
        private String industry;
        private String kind;
        private LocalDate annDate;
        private LocalDate endDate;
        /** 监管窗口总长（D+N 的 N，SEVERE/EXCH=10、ZD=5）。 */
        private int totalDays;
        /** 当前已走到 D+N（出池后为 null）。 */
        private Integer currentOffset;
        /** 是否已走完整个监管窗口（出池）。 */
        private boolean done;
        private List<DayCell> daily = new ArrayList<>();
        /** 区间最高连板。 */
        private int maxBoard;
        /** 拐点日：第一次断板/跌停/转绿是 D+几，无 = null。 */
        private Integer turnDay;
        /** 区间累计涨跌幅 %（D+1 起连乘，停牌沿用上一刻）。 */
        private BigDecimal cumChg;
        /** 绕异动（监管期内仍涨停且未核按钮）。 */
        private boolean avoid;
        /** 状态名：绕异动/先扬后抑/监管生效/停牌/已出池。 */
        private String status;
        /** 状态色板：危险/警示/中性/正常。 */
        private String statusTone;
        /** 状态的一句话依据。 */
        private String why;
    }

    /** 监管期内某一天的一个格子。 */
    @Data
    public static class DayCell {
        private int offset;
        private LocalDate date;
        private BigDecimal chg;
        private Integer consecutive;
        private String pool;
        private boolean suspended;
        /** 事件符号说明：停牌/跌停/断板/核按钮。 */
        private String event;
    }

    /** 状态五态。 */
    @Data
    public static class Status {
        private final String name;
        private final String tone;
        private final String reason;

        public Status(String name, String tone, String reason) {
            this.name = name;
            this.tone = tone;
            this.reason = reason;
        }
    }

    /** 累计涨幅曲线（≤5 只时返回）。 */
    @Data
    public static class Series {
        private String name;
        private List<BigDecimal> points = new ArrayList<>();
    }
}