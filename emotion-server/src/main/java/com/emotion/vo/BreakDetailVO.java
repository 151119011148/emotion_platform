package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * 破壁详情：曲线上 ☆（试探破壁）/ ★（破壁成功）那两天的辅助验证面板。
 *
 * <p><b>判定不在这儿算</b>——哪天是试探、哪天算破壁成功，唯一出处是
 * {@link com.emotion.service.TiantiService#detectBreaks}，本 VO 只是把那一天的线、主角、
 * 助攻、盘口、情绪闸门和次日结算摆在一起。
 *
 * <p>首板助攻与盘口在这里<b>只算、只展示</b>，按他的口径不当开仓闸门：
 * 「试探日开仓」的主信号是「首次追平破壁线 + 次日续板」，助攻只回答"这个题材有没有梯队"。
 * 因此所有数值字段都可能是 null——<b>null 是"不知道"，不是"0 只"</b>：
 * 2026-08-03 之前的名义天梯只有板高没有逐只明细，那种日子兜成 0 会把"没有助攻"
 * 伪造进一段根本没法验证的历史里。
 */
@Data
public class BreakDetailVO {

    /** {@link #event} 的取值：这天有票首次追平破壁线，成不成等次日续板。 */
    public static final String EVENT_PROBE = "PROBE";
    /** 这天有票续板破掉了那条线，新周期立起来。 */
    public static final String EVENT_BREAK = "BREAK";
    /** 这天没有破壁事件——面板不显示，只有直接打接口才拿得到。 */
    public static final String EVENT_NONE = "NONE";

    /** {@link Outcome#getResult()} 的取值：次日续板 = 破壁成功。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    /** 次日没续板（滞涨或掉出名单）= 这次破壁没兑现。 */
    public static final String OUTCOME_FAILED = "FAILED";
    /** 次日还没有盘面明细：判不了，不是"没续板"。 */
    public static final String OUTCOME_PENDING = "PENDING";

    private LocalDate tradeDate;
    /** 事件类型：PROBE=试探破壁日 / BREAK=破壁成功日 / NONE=这天没有破壁事件。 */
    private String event;
    /**
     * 这天在连板高度曲线上<b>有没有点</b>：false=那天没有任何涨停明细、破壁判不起来（缺数，不是一个结论）；
     * true 而 {@link #event} 仍是 NONE，才是"曲线算完了、这天确实没有 ☆/★"。复算面板靠这一格把
     * 「判不了」和「已经不成立」分开——前者不许采纳，后者建议作废。
     */
    private Boolean curvePoint;
    /** 当天要追平的那条破壁线 L。 */
    private Integer ceiling;
    /** 挂着这条线的定线票。 */
    private StockRef lineStock;
    /** 这条线的高度是哪天哪只票打出来的（先立起这一级的人记名，唯一改记是断板钉线）。 */
    private LocalDate lineOriginDate;
    private StockRef lineOriginStock;
    /** 挂账的旧龙板高 H（还没收复的那个高度），仅阶梯钉线期间有值。 */
    private Integer oldDragonHeight;
    /** 当天是否处于钉线倒计时（混沌期）。 */
    private Boolean chaos;

    /** 主角：试探日是追线的那只，成功日是续板成功的那只。 */
    private StockRef subject;
    /** 成功日：它追平的那条线高（试探日当天挂着的 L），不是结算日已经抬高的线。 */
    private Integer prevHigh;
    /** 成功日：之前那次试探发生在哪天，破壁股是哪只。 */
    private LocalDate probeDate;

    /** 逐只盘口明细在不在（2026-08-03 之前的名义天梯为 false）。 */
    private Boolean detailAvailable;
    /** 明细不在的原因，界面上顶在助攻/盘口两块的位置，免得空白被读成"没有助攻"。 */
    private String detailMissingReason;

    private Assist assist;
    private BoardInfo board;
    private MarketGate gate;
    /** 情绪闸门里按他写的数（炸板率&gt;50%、昨日涨停溢价&lt;0、阶段退潮）判出来的降级提示。 */
    private List<String> gateWarnings;
    private Outcome outcome;
    /**
     * 立成节点会动谁的分数：节点票 {@code node_stock} 撞进既有打分索引，
     * 同股只认 D0 最近的那条。点「立为节点」之前先看这一句。
     */
    private String scoreImpact;

    /** 一只票的引用：代码 / 名称 / 当天板高 / 归类后的行业。 */
    @Data
    public static class StockRef {
        private String code;
        private String name;
        private Integer board;
        private String industry;
    }

    /**
     * 助攻：同属性的首板/二板/三板以上家数，加当天首板的<b>广度</b>。
     *
     * <p>他看的是广度不是个数——"全是孤板、首板散到五六个不同题材"就不认，所以
     * {@link #firstBoardTotal} 与 {@link #firstBoardIndustries} 与 {@link #sameIndustryFirst}
     * 要并排看：同属性 2 只、当天首板 60 只散在 20 个行业，和同属性 2 只、当天首板 5 只挤在 2 个行业，
     * 是两回事。
     */
    @Data
    public static class Assist {
        /** 同属性首板（涨停池 1 板且封住）家数；明细缺失时 null。 */
        private Integer sameIndustryFirst;
        /** 同属性 2 板家数（不含主角自己）。 */
        private Integer sameIndustrySecond;
        /** 同属性 ≥3 板家数。 */
        private Integer sameIndustryThirdPlus;
        /** 助攻合计：首板 + 二板 + 三板以上，>=2 即"题材有梯队"。 */
        private Integer total;
        /** 梯队立住＝{@link #total} ≥ 2；只作展示，不进 ready、不改权重。 */
        private Boolean ladderOk;
        /** 当天首板封住总家数（广度分母）；明细缺失时 null。 */
        private Integer firstBoardTotal;
        /** 当天首板分布在几个不同行业；散得越开越像补涨而不是梯队。明细缺失时 null。 */
        private Integer firstBoardIndustries;
        /** 同属性首板名单。 */
        private List<StockRef> firstStocks;
        /** 同属性 2 板及以上的跟进名单（不含主角）。 */
        private List<StockRef> followStocks;
    }

    /** 主角当天的盘口：一字/换手、开板次数、封成比、换手率；明细缺失时逐字段 null。 */
    @Data
    public static class BoardInfo {
        /** ONE_LINE 一字 / T_SHAPE T字 / TURNOVER 换手。 */
        private String pattern;
        /** 封板形态分档：一字/早盘秒板/早盘直线/早盘板/上午板/午后板/尾盘板。 */
        private String sealForm;
        /** 首次封板时间 HHMMSS。 */
        private Integer firstSealTime;
        /** 日内开板次数；他说的"烂板"就读这个数。 */
        private Integer breakCount;
        /** 换手率 %；"缩量秒过线"要和 {@link #pattern} 一起看。 */
        private BigDecimal turnoverRate;
        /** 封成比＝封单额/成交额，越大越"不给上车"。 */
        private BigDecimal sealRatio;
        private BigDecimal sealAmount;
        private BigDecimal floatMv;
        private BigDecimal changePct;
        /** 一字断魂刀打标（连续锁死 + 小盘 + 封成比≥3 + 换手<5%），既成口径，直接引用。 */
        private Boolean oneWordKilling;
    }

    /** 情绪闸门：当天全局客观读数，与节点复算的前置过滤器同源。 */
    @Data
    public static class MarketGate {
        /** 当天炸板率 %（t_market_daily 全局值）。 */
        private BigDecimal brokenBoardRate;
        /** 昨日涨停溢价 %。 */
        private BigDecimal yesterdayLimitPremium;
        /** 当天涨停家数。 */
        private Integer limitUpCount;
        /** 阶段（退潮/发酵…）：只有账号的复盘行里有，没复盘就 null。 */
        private String stage;
    }

    /** 次日结算：试探追的那只续没续板——SUCCESS 就是破壁成功，FAILED 就是不认。 */
    @Data
    public static class Outcome {
        /** SUCCESS=次日续板 / FAILED=次日没续板或掉榜 / PENDING=次一交易日明细还没落库。 */
        private String result;
        private LocalDate nextDate;
        /** 主角在次日的板高；0=已经掉出连板名单。 */
        private Integer nextBoard;
        private String reason;
    }
}
