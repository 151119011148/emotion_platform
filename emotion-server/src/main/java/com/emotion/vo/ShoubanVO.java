package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * 首板池（时间截面 PRD v2.0）：<b>纯 T 日试错端</b>。
 * 两表——今日首板「封住」（涨停池 1 板）与「未封住」（炸板池中昨日未涨停者）；
 * Summary 只含 T 日指标（数量/封板率/炸板率/一字数/均封单/题材聚集）。
 * 1进2晋级/首板溢价/1进2大面是 T-1→T 兑现口径，已迁至连板生态低位层（/tianti 2 板层）。
 */
@Data
public class ShoubanVO {

    private LocalDate tradeDate;
    /** 上一交易日明细是否落库（决定首板炸板能否判定）。 */
    private Boolean prevAvailable;
    private Summary summary;
    /** 今日首板封住表。 */
    private List<Row> sealed;
    /** 今日首板炸板表（昨日未在涨停池的 ZB 行）。 */
    private List<Row> bombed;

    @Data
    public static class Summary {
        /** 首板封住家数（涨停池 consecutive=1）。 */
        private Integer sealedCount;
        /** 首板炸板家数（昨日明细可判定口径）。 */
        private Integer bombedCount;
        /** 首板封板率 = 封住 ÷（封住 + 首板炸板），分母 0 时 null。 */
        private Double sealedRate;
        /** 首板炸板率 = 100 − 封板率。 */
        private Double bombRate;
        /** 一字首板家数（ONE_LINE）。 */
        private Integer yiziCount;
        /** 一字首板占比 %（只在能判形态的样本里）。 */
        private Double yiziRatio;
        /** 首板均封单额（元）。 */
        private BigDecimal avgSealAmount;
        /** 首板最热行业。 */
        private String topIndustry;
        /** 最热行业首板家数。 */
        private Integer topIndustryCount;
        /** 首板题材聚集度 % = 最热行业首板数 / 有行业归属首板数。 */
        private Double themeGatherPct;
    }

    @Data
    public static class Row {
        private String code;
        private String name;
        private String industry;
        private BigDecimal changePct;
        /** 自涨停回撤 %，只有炸板行有。 */
        private BigDecimal pullbackPct;
        /** 是否主线行业。 */
        private Boolean inMain;
        /** 日内开板次数。 */
        private Integer breakCount;
        /** 封单额（元），只有封住行有。 */
        private BigDecimal sealAmount;
        /** 首次封板时间 HHMMSS。 */
        private Integer firstSealTime;
        /** 形态：ONE_LINE/T_SHAPE/TURNOVER，炸板行为 null。 */
        private String pattern;
    }
}
