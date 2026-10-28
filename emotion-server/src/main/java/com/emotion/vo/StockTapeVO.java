package com.emotion.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一只票一天的分时：分钟序列 + 集合竞价定格快照。
 *
 * <p>逐分钟的量与涨幅是<strong>读时算</strong>出来的，库里只存累计原值：
 * 与 {@code t_daily_bar}「pct 不落库」同一个选择——存增量就得假设「上一根一定在库里」，
 * 而某天缺根时得回头补，读口径不该有这个前提。
 *
 * <p>{@code available=false} 时前端不画空图，而是把 {@code emptyReason} 印出来。
 * 这里的"没有"有三种，含义完全不同，不能合成一句话：
 * 那天全市场根本没抓（早于 2026-10-28，源只给当天，永久缺失）；
 * 那天抓了但这只票不在名单里（只抓涨停池∪炸板池）；
 * 以及当天的抓取还没排到（19:12 之后才有）。
 */
@Data
public class StockTapeVO {

    private String code;
    private String name;
    private LocalDate tradeDate;
    /** 该日分钟序列是否入库。false 时 {@code points} 为空，界面直接印 {@code emptyReason}，不画空图。 */
    private boolean available;
    /**
     * 为什么没有。这句必须说中是哪一种：早于上线日（源只给当天，永久缺失）、
     * 当天那一趟没跑成、还是当天抓了但名单里没有这只（只抓涨停池∪炸板池）。
     * 具体只数写在句子里，不再单开一个字段——它只在缺数时有意义，多一个字段就多一处能说谎的地方。
     */
    private String emptyReason;

    /** 涨幅的分母。取不到就是 null，此时 {@code Point.pct} 与竞价涨幅一起为 null，价格线照画。 */
    private BigDecimal prevClose;
    /** prevClose 的来路，见 {@link #PREV_AUCTION} 等常量。 */
    private String prevCloseSource;

    private List<Point> points = new ArrayList<>();
    private Auction auction;

    /** 09:26 快照里的 prev_close，源给的不复权原值，最准。三条来路精度不同，界面要能说清用的是哪一条。 */
    public static final String PREV_AUCTION = "AUCTION";
    /** 当日明细的收盘价与涨幅反推：{@code 收盘 ÷ (1+涨幅/100)}，因为涨幅只给两位小数，有分位级误差。 */
    public static final String PREV_MARKET_STOCK = "MARKET_STOCK";
    /** 库里上一交易日日 K 收盘：<b>前复权</b>序列，期间除过权就会偏低，所以只在严格等于前一交易日时才用。 */
    public static final String PREV_DAILY_BAR = "DAILY_BAR";
    /** 三条都没命中：价格线照画，涨幅轴不画。 */
    public static final String PREV_NONE = "NONE";

    @Data
    public static class Point {
        /** HHMM，源给什么读什么（实测 0930…1530，中间跳过午休）。 */
        private Integer minute;
        private String time;
        private BigDecimal price;
        private BigDecimal pct;
        private BigDecimal vwap;
        /** 这一分钟的量：累计值差分。首根 0930 是「竞价撮合 + 开盘一分钟」，所以它偏大是对的。 */
        private Long volumeHand;
        private BigDecimal amountWan;
    }

    @Data
    public static class Auction {
        /** 09:26 有没有抓到这一只的快照。false 时下面全是 null，界面印 emptyReason。 */
        private boolean available;
        private String emptyReason;
        private BigDecimal auctionPrice;
        private BigDecimal pct;
        private Long volumeHand;
        private BigDecimal amountWan;
        private BigDecimal bid1Price;
        /** 未匹配买量(手)：竞价定格时还没撮合掉的买单，一字板"封死"的程度就看它。 */
        private Long bid1Hand;
        private BigDecimal ask1Price;
        private Long ask1Hand;
        private BigDecimal limitUpPrice;
        /** 竞价成交价 == 当日涨停价，即「一字板的成因」。任一值为 null 时为 null（不知道，而不是否）。 */
        private Boolean sealedAtOpen;
        private LocalDateTime capturedAt;
    }
}
