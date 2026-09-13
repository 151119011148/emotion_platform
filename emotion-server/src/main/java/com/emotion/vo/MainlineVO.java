package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * 主线详情（PRD P6）：主线五要素 + 生命周期 + 龙头分工 + 轮动信号。
 * 数据全部出自 {@link com.emotion.service.PrdMetricsService} 快照与当日人工列，
 * 与打分引擎同源——页面上的每个数都能在 score-detail 里找到同一个值。
 */
@Data
public class MainlineVO {

    private LocalDate tradeDate;
    /** 日内核心行业（涨停聚集度最高者）；当日无涨停=null，生命周期亦为 null。 */
    private String mainIndustry;
    /**
     * 日内核心是否已收集为主线龙头：当日该行业涨停≥5 家且连续 3 个热度交易日（含今天）。
     * false 时页面仍展示日内核心，但标注「热度未满 3 日，暂未成主线」。
     */
    private Boolean mainlineConfirmed;
    /** 生命周期阶段：萌芽/确认/扩散/亢奋/退潮。 */
    private String lifecycleStage;
    /** 固定五段顺序，供前端画 [萌芽]─[确认]─[扩散]─[亢奋]─[退潮] 轨道。 */
    private List<String> lifecycle;

    // ---- 五要素 ----
    private Double ztGatherPct;
    private Double heightGatherPct;
    /**
     * 成交额聚集度 %（涨停股口径，唯一口径）：主线涨停股 amount / 全部涨停股 amount；
     * 自动计算、不接受人工覆盖；amount 整列缺失（更早历史）=null。
     */
    private Double amountGatherPct;
    /** 催化剂硬度 1-5（进分的有效值：题材行未设时为默认 3）；无匹配题材行=null。 */
    private Integer catalystHardness;
    /** 主线连续活跃天数。 */
    private Integer persistenceDays;
    /** 题材行名称与主线行业是否对上（没对上时硬度的可信度要打折，前端提示用）。 */
    private Boolean mainThemeMatched;

    // ---- 规模速览 ----
    private Integer mainZt;
    private Integer ztTotal;
    private Integer mainMaxBoard;
    private Integer maxBoard;

    // ---- 龙头分工 ----
    private Dragon dragon;
    /**
     * 日内核心板块内的最高板龙头（板块视角），与全市场空间板 {@link #dragon}（市场视角）区分：
     * 两者不同行时就是"龙头与主线错位"（如市场 H=4 在家居用品、元件板块内最高仅 2 板）。
     */
    private Member sectorLeader;
    /** 总龙头是否属于日内核心板块（false=错位无合力，D2 已据此 ×0.9）。 */
    private Boolean dragonAligned;
    private List<Member> zhongJun;
    private Integer genFengCount;
    /** 卡位（他题材高标）；sealed=false 表示昨日高标今日炸板。 */
    private Member kaWei;
    private List<Member> fanBao;

    // ---- 轮动 ----
    private List<String> rotationSignals;

    // ---- 双轨 v0.2：雷达区（候选池）+ 主线打标 ----
    private List<RadarRow> radar;
    /** 雷达区题材表：radar 按题材归并后的行（只含用户已登记题材的行业）。 */
    private List<RadarRow> radarThemes;
    /** 当日涨停聚集度最高行业（候选榜首，雷达区第 0 行）；与 {@link #mainIndustry} 错位时即"候选 vs 晋级"的典型。 */
    private String radarTopIndustry;
    /** 当日是否已有主线：radar 任一 ≥3天 或存在人工 t_mainline_mark。 */
    private Boolean hasMainline;
    /** D2 评分对象是否由人工主线标记产生（前端标 🏷人工）。 */
    private Boolean manuallyMarked;
    /** 无主线打标：如 "⚠️无主线，仅日内核心炒作（最强板块仅 1 天）"；有主线=null。 */
    private String mainlineSignal;

    /** 雷达区单行：当日一个行业板块（候选池，不打 D2 分）。 */
    @Data
    public static class RadarRow {
        private String industry;
        /** 该行业名匹配到的用户题材（t_theme.name==industry）；null=未登记题材。 */
        private String theme;
        private Integer zt;
        private Double ztGatherPct;
        private Integer maxBoard;
        private Integer persistenceDays;
        /** NEW(1天🆕)/WATCH(2天)/MAIN(≥3天⭐)。 */
        private String flag;
        private Boolean isMainline;
        private Member leader;
    }

    /** 总龙头卡：action=PROMOTE/HOLD/BREAK/ABSENT。 */
    @Data
    public static class Dragon {
        private String code;
        private String name;
        private String industry;
        private Integer board;
        /** 该总龙头是否属于日内核心板块；false 时前端必须打"非本板块·龙头错位"标。 */
        private Boolean inMainSector;
        private String action;
        private Boolean promoted;
        /** 判定依据（选取规则 + 今日状态证据）。 */
        private String reason;
        private BigDecimal changePct;
        private BigDecimal pullbackPct;
    }

    /** 龙头分工成员。sealed 仅卡位行有意义。 */
    @Data
    public static class Member {
        private String code;
        private String name;
        private String industry;
        private Integer board;
        private BigDecimal changePct;
        private Boolean sealed;
    }
}
