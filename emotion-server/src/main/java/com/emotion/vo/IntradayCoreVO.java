package com.emotion.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 日内核心页（PRD 页面A）：当日 t_market_stock 三池按东财行业(hybk→industry)聚合的板块强度排名。
 *
 * <p>纯公开行情、不绑 user_id；聚合口径与 {@link com.emotion.service.PrdMetricsService} 同源
 * （industry 是行业不是题材；industry 为空的行计入全市场总数但不进板块排名）。
 * 强度分为页面展示口径，不进打分引擎。
 */
@Data
public class IntradayCoreVO {

    private LocalDate tradeDate;
    /** 当日是否落过涨停池明细。false=没数据，前端不画排名而不是画一屏 0。 */
    private boolean available;

    /** 全市场涨停家数（含 industry 为空的行）。 */
    private int ztTotal;
    /** 全市场炸板家数。 */
    private int zbTotal;
    /** 全市场最高连板 H（含 industry 为空的行），板块高度分的分母。 */
    private int globalMaxBoard;

    private List<Sector> sectors = new ArrayList<>();

    @Data
    public static class Sector {
        /** 东财行业名（t_market_stock.industry）。 */
        private String industry;
        /** 涨停家数（pool=ZT）。 */
        private int lbCount;
        /** 板块最高板。 */
        private int maxBoard;
        /** 封单总额（元）= SUM(seal_amount)。 */
        private BigDecimal sealSum = BigDecimal.ZERO;
        /** 一字板数：首封≤09:30 且全天 0 开板（StockPatterns.ONE_LINE，fbt 缺失不计入）。 */
        private int yiziCnt;
        /** 回封板数：ZT 中 break_count&gt;0（T字/换手回封）。 */
        private int reopenCnt;
        /** 炸板家数（pool=ZB）。 */
        private int zbCnt;
        /** 大面家数：ZB 池中 big_loss=1（ZT 行按定义不可能大面）。 */
        private int bigLossCnt;
        /** 覆盖的连板层数（COUNT DISTINCT consecutive）。 */
        private int tierCnt;
        /** 实际覆盖的板层，高→低，如 [4,3,2,1]。 */
        private List<Integer> tiers = new ArrayList<>();
        /** 板块龙头：最高连板，同板取涨幅最大、再同取代码最小。 */
        private String leaderCode;
        private String leaderName;
        private Integer leaderBoard;

        /** 板块强度分 0-100（25 家数 + 25 高度 + 20 封单 + 15 一字占比 + 15 梯队 − 大面罚分）。 */
        private BigDecimal strength;
        /** 以下为强度分的透明分项，供 tooltip 对算式，不参与别的计算。 */
        private Double cntScore;
        private Double hScore;
        private Double sealScore;
        private Double yiziScore;
        private Double tierScore;
        private Integer penalty;
    }

    /** 板块下钻：该行业当日涨停池逐只，按连板高→低，直接喂天梯/StockCard。 */
    @Data
    public static class SectorStocks {
        private LocalDate tradeDate;
        private String industry;
        private boolean available;
        private List<Item> stocks = new ArrayList<>();
    }

    @Data
    public static class Item {
        private String code;
        private String name;
        private String industry;
        /** 连板数（consecutive 缺失按 1，与全系统口径一致）。 */
        private int board;
        private BigDecimal changePct;
        private BigDecimal sealAmount;
        private Integer firstSealTime;
        private Integer breakCount;
        /** ONE_LINE / T_SHAPE / TURNOVER / null（fbt 缺失）。 */
        private String pattern;
    }
}
