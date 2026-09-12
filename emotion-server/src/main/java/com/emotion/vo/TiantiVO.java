package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * 连板生态（PRD P2）：当日涨停池 ≥2 板按<b>板高逐层</b>组成天梯，逐只挂龙头分工标签与形态
 * （一字/T字/换手）；3 板及以上层级把晋级失败名单并入同层（前端灰色标注）。标签、形态、晋级均与打分引擎同源。
 */
@Data
public class TiantiVO {

    private LocalDate tradeDate;
    /** 全市场最高连板 H。 */
    private Integer maxBoard;
    /** 日内核心行业（涨停聚集度最高者）；连续 3 热度交易日才确认为主线龙头。 */
    private String mainIndustry;
    /** 日内核心是否已被收集为主线龙头（连续热度 ≥3 个交易日）。 */
    private Boolean mainlineConfirmed;
    private Double ztGatherPct;
    private Double heightGatherPct;
    private Integer persistenceDays;
    /** 当日涨停家数。 */
    private Integer ztTotal;
    /** 当日炸板家数。 */
    private Integer zbTotal;
    /** 当日连板家数（涨停池中连板数 ≥2）。 */
    private Integer lbTotal;

    /**
     * 天梯层，从最高板到 2 板逐层一个；空层保留（断档本身就是信息）。
     * 旧的四层（极高/中高/中/低）动态归属用 {@link Level#layerLabel} 表达，分层口径与打分一致。
     */
    private List<Level> levels;

    @Data
    public static class Level {
        /** 本层板高 n（2..H）。 */
        private Integer board;
        /** 打分四层归属：极高位/中高位/中位/低位。 */
        private String layerLabel;
        /** 今日在板的个股（含晋级成功与持稳），按封单金额从大到小排序。 */
        private Integer count;
        private List<Row> rows;
        /**
         * 晋级失败名单（昨日 n-1 板今日未封住 n 板）。页面仅在 n≥3 层展示，
         * 2 板层（首板晋级失败）样本太大，走首板生态页口径。
         */
        private List<FailedRow> failed;
    }

    @Data
    public static class Row {
        private String code;
        private String name;
        private String industry;
        /** 连板数。 */
        private Integer board;
        private BigDecimal changePct;
        /** 日内开板次数。 */
        private Integer breakCount;
        /** 龙头分工标签：总龙头/中军/跟风/卡位/反包；普通股=null。 */
        private String role;
        /** 昨日同代码连板数=今日-1 即 true；昨日无明细=null。 */
        private Boolean promoted;
        /** 封单额（元）。 */
        private BigDecimal sealAmount;
        /** 首次封板时间 HHMMSS。 */
        private Integer firstSealTime;
        /** 形态：ONE_LINE 一字 / T_SHAPE T字 / TURNOVER 换手；历史明细=null。 */
        private String pattern;
    }

    /** 晋级失败：昨日 n-1 板，今日未封住 n 板。 */
    @Data
    public static class FailedRow {
        private String code;
        private String name;
        private String industry;
        private Integer prevBoard;
        /** ZT=今日仍涨停（停在低板）/ ZB=今日炸板 / DT=今日跌停 / GONE=未触板（免费源无逐只行情，最新交易日尝试腾讯报价补全）。 */
        private String todayStatus;
        private BigDecimal changePct;
        private BigDecimal pullbackPct;
        private String pattern;
    }
}
