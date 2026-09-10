package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * 首板池（PRD P3）：两表——今日首板<b>封住</b>（涨停池 1 板）与今日首板<b>炸板</b>
 * （炸板池中昨日未涨停者；昨日有明细才能判定"首板尝试"）。
 * 汇总块给 1 进 2 晋级率与"昨日首板今日均溢价"（档位表 board=1 行）。
 */
@Data
public class ShoubanVO {

    private LocalDate tradeDate;
    /** 上一交易日（档位口明细）。null=昨天没有落库明细。 */
    private LocalDate prevDate;
    private Boolean prevAvailable;
    private Summary summary;
    /** 今日首板封住表。 */
    private List<Row> sealed;
    /** 今日首板炸板表。 */
    private List<Row> bombed;
    /** 昨日首板今日的 1 进 2 成败明细（首板溢价的逐只名单）。 */
    private PromoDetail promoDetail;

    @Data
    public static class Summary {
        private Integer sealedCount;
        /** 首板炸板家数（可判定口径）。 */
        private Integer bombedCount;
        /** 封板率 = 封住 ÷（封住 + 首板炸板），分母 0 时 null。 */
        private Double sealedRate;
        /** 昨日首板家数（1 进 2 晋级率的分母）。 */
        private Integer prevFirstCount;
        /** 昨日首板今日晋级 2 板家数。 */
        private Integer promoCount;
        private Double promoRate;
        /** 昨日首板今日均溢价 %（t_premium_tier board=1 档），未落档位=null。 */
        private Double prevFirstPremiumPct;
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

    /** 昨日首板（n=1）今日的 1 进 2 成绩单。 */
    @Data
    public static class PromoDetail {
        /** 昨日首板家数（分母）。 */
        private Integer prevCount;
        private Integer promotedCount;
        private Double promoRate;
        /** 昨日首板今日均溢价 %（档位表 board=1 口径；逐只名单覆盖不到全市场时以它为准）。 */
        private Double avgPremiumPct;
        private List<Row> success;
        private List<FailedRow> failed;
    }

    /** 首板晋级失败的逐只状态（与连板生态 FailedRow 同构）。 */
    @Data
    public static class FailedRow {
        private String code;
        private String name;
        private String industry;
        /** ZT=今日仍涨停但没到 2 板 / ZB=今日炸板 / GONE=未触板（明细未覆盖）。 */
        private String todayStatus;
        private BigDecimal changePct;
        private BigDecimal pullbackPct;
        private String pattern;
    }
}
