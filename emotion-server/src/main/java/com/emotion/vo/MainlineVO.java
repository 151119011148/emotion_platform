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
    /** 成交额聚集度 %，只有人工口径（t_daily_record.manual_amount_gather_pct），未填=null。 */
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
    private List<Member> zhongJun;
    private Integer genFengCount;
    /** 卡位（他题材高标）；sealed=false 表示昨日高标今日炸板。 */
    private Member kaWei;
    private List<Member> fanBao;

    // ---- 轮动 ----
    private List<String> rotationSignals;

    /** 总龙头卡：action=PROMOTE/HOLD/BREAK/ABSENT。 */
    @Data
    public static class Dragon {
        private String code;
        private String name;
        private String industry;
        private Integer board;
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
