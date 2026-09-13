package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * D5 高位生态（阵眼·抱团·监管）融合视图，{@code GET /api/d5/high} 的响应体。
 *
 * <p>结构对齐《D5 融合版 PRD》第十节：date/H/score/level + 四子项块 + 监管池 + 交叉信号。
 * 维分本身由 BoardScoreCalculator 按注册表树算出，这里只装取数层的事实与子项分，便于卡片直出。
 */
@Data
public class HighEcoVO {

    private LocalDate date;
    /** 当日最高连板 H。 */
    private int h;
    /** D5 维分（0-100）；四子项全未评时 null。 */
    private Integer score;
    /** 健康(80+)/可控(60+)/警戒(40+)/危险(20+)/崩塌(<20)。 */
    private String level;

    private AnchorBlock anchor;
    private CoalitionBlock coalition;
    private PressureBlock pressure;
    private FeedbackBlock feedback;
    /** 在列且进分的监管股（SEVERE/EXCH）；例行 ZD 不进此表。 */
    private List<MonitorItem> monitorPool = new ArrayList<>();
    private List<Signal> signals = new ArrayList<>();
    /** 取数口径与未评原因（无事件窗/无阵眼/缺价等），卡片直接展示。 */
    private List<String> notes = new ArrayList<>();
    /** 强制风控（引擎 force flag 触发的红条）；未触发为 null。 */
    private ForceRisk forceRisk;

    /** D5 强信号守卫触发的强制风控红条。 */
    @Data
    public static class ForceRisk {
        private boolean triggered;
        private String reason;
        /** 封顶后的 D5 总分（崩塌顶，≤{@code 20}）。 */
        private Integer capScore;
    }

    /** 子项1：阵眼个体（人工 t_anchor，起止区间内恒定，断板日仍跟踪）。 */
    @Data
    public static class AnchorBlock {
        /** 当天是否有人工在位阵眼；false 时本子项整支未评（剔出分母，不兜 0）。 */
        private boolean configured;
        private List<AnchorItem> items = new ArrayList<>();
        /** 多阵眼按角色权重加权后的子项分。 */
        private Integer score;
    }

    @Data
    public static class AnchorItem {
        private Long id;
        private String code;
        private String name;
        private String industry;
        /** ZONG/FENZHI/BUZHANG/FANBAO（兼容 CYCLE/LEADER）。 */
        private String role;
        private String roleLabel;
        private LocalDate startDate;
        private LocalDate endDate;
        /** 生效第几个交易日（按池明细日期计，无明细时按自然日）。 */
        private Integer activeDays;
        /** 今日连板（断板日取昨板，便于看它从多高掉下来）。 */
        private Integer consecutive;
        private BigDecimal chg;
        private BigDecimal sealAmount;
        /** JIN_JIA/FAN_BAO/HANG_TIAO/DUAN_BAN/HE_PAN。 */
        private String action;
        private String actionLabel;
        private boolean realTop;
        private String realTopCode;
        private Integer actionScore;
        private Integer heightScore;
        private Integer sealScore;
        private Integer consistScore;
        /** 单阵眼 40/25/20/15 合成。 */
        private Integer score;
        private Lifecycle lifecycle;
        /** 主线错位时的人话告警；未错位为 null。 */
        private String consistWarn;
    }

    /** 阵眼生命周期（起止区间内，池行回溯，不额外打日 K）。 */
    @Data
    public static class Lifecycle {
        private Integer maxConsecutive;
        private Integer breakTimes;
        private Integer bigLossTimes;
        /** 区间内最近一起进分监管类型标签（无则 null）。 */
        private String monitor;
    }

    /** 子项2：抱团与资金。 */
    @Data
    public static class CoalitionBlock {
        /** 高位阈值（H>=5?5:max(3,H-1)）。 */
        private int highThreshold;
        private int highCount;
        private int midCount;
        private int lowCount;
        /** 高位封单额 / 全市场涨停封单额（0-1）。 */
        private BigDecimal highSealRatio;
        /** consecutive==H 的家数。 */
        private int topCount;
        private boolean hasGap;
        /** 高位溢价 %（t_premium_tier 高位档加权）。 */
        private BigDecimal highPrem;
        /** 高位晋级率 0-1。 */
        private BigDecimal highJr;
        private Integer structureScore;
        private Integer strengthScore;
        private Integer score;
        /** 强信号守卫（无头抱团折扣等）的人话说明；未触发为 null。 */
        private String adjust;
    }

    /** 子项3：监管压制。 */
    @Data
    public static class PressureBlock {
        /** SEVERE/EXCH 在列家数。 */
        private int survCount;
        private int highSurvCount;
        /** 高位监管股 / 在列家数（0-1）。 */
        private BigDecimal highSurvRatio;
        /** 同行业最多监管家数。 */
        private int maxSectorSurv;
        private String maxSectorName;
        private Integer score;
        /** 强信号守卫（监管反馈否决权）的人话说明；未触发为 null。 */
        private String adjust;
    }

    /** 子项4：监管反馈。 */
    @Data
    public static class FeedbackBlock {
        /** 进分监管股今日算术平均涨幅 %（缺价者不计）。 */
        private BigDecimal survAvgChg;
        /** 核按钮（DT 池或大面）家数。 */
        private int survNuke;
        private Integer score;
    }

    /** 监管池一行。 */
    @Data
    public static class MonitorItem {
        private String code;
        private String name;
        private String industry;
        private Integer consecutive;
        private String kind;
        private LocalDate annDate;
        private BigDecimal chg;
        /** 涨停(无视监管)/红盘震荡/绿盘分歧/断板/核按钮/缺价。 */
        private String status;
    }

    /** 交叉信号（不进分，直接触发标签/风控）。 */
    @Data
    public static class Signal {
        private String code;
        private String label;
        /** info/warn/danger。 */
        private String level;

        public Signal() {
        }

        public Signal(String code, String label, String level) {
            this.code = code;
            this.label = label;
            this.level = level;
        }
    }
}
