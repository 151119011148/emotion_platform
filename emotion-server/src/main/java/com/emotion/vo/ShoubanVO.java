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
 *
 * <p>另带一份「站上年线 + 题材在天梯里没有」的首板筛选结果（{@code screen*} 与各行的
 * {@code screen*} 字段）：判据与计算在 {@code FirstBoardScreenService}，15:30 落库，
 * <b>本页只读留档</b>——首板生态整页原本一处网络都不打，不能因为加了一节年线就变成等一串上游往返。
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

    /**
     * 当日首板筛选<strong>有没有算过</strong>：false 是「还没判」，true 且 {@code screenPassedCount=0}
     * 才是「判了、一只都没过」。两者在界面上必须长得不一样——后者是真答案，前者只是排班没跑到。
     */
    private Boolean screenAvailable;
    /** 判过的首板只数（含未通过的）。 */
    private Integer screenedCount;
    /** 两条判据都过的只数。 */
    private Integer screenPassedCount;
    /** 当日天梯（2 板及以上）覆盖的题材并集只数——大面积淘汰时这句话是答案的一半。 */
    private Integer ladderConceptCount;

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

        /**
         * 当日<strong>有没有</strong>对这只票做过年线/题材筛选：
         * {@code null}＝15:30 那趟还没算（或它是炸板行，本就不在筛选范围内），
         * 字段全空不代表它不合格。
         */
        private Boolean screened;
        /** 两条判据都过：收盘站上 250 日均线，且题材在当日连板天梯里一只都没有。 */
        private Boolean screenPassed;
        /** 判定去向：PASS / BELOW_MA250 / THEME_IN_LADDER / NO_ANNUAL_LINE / NO_CONCEPT / NO_CLOSE。 */
        private String screenReason;
        /** 判定用的当日收盘价（取涨停池那一份，与年线同源，便于核对「收盘 vs 年线」）。 */
        private BigDecimal closePrice;
        /** 250 日均线（前复权，含当日收盘）。 */
        private BigDecimal ma250;
        /** (收盘 − 年线) ÷ 年线 × 100，正数即站上年线。 */
        private BigDecimal aboveMaPct;
        /** 该股所属通达信题材简称（判据用的就是这一份，不是引擎分组的行业）。 */
        private List<String> concepts;
    }
}
