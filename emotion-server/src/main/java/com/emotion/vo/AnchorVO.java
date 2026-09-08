package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 周期阵眼面板的数据：某日在位的每一只阵眼，连它的跨度和当日反馈一起给。
 *
 * <p>available=false 是"没设阵眼"，与"设了但那天取不到行情"（items 为空或某只 available=false）
 * 是两件事，前端文案必须分开：前者第 8 维不计入分母，后者是缺数据要告警。
 *
 * <p>跨度里除了起止日，其余数字全是从日 K 现算的，库里一个都不存。
 */
@Data
public class AnchorVO {

    private LocalDate tradeDate;
    /**
     * 第 8 维今天进不进分（等价于 score 非 null）。false 有两种，靠 items 分得开：
     * 空 = 这天没有在位的阵眼（未设）；非空 = 设了但一只都取不到行情（周末、停牌），后者看 note。
     * 两者都不写 0 分。
     */
    private boolean available;
    /** 一句话说清这块面板现在的状态，界面上直接显示，不让人猜 0 分哪来的。 */
    private String note;
    /** 多只在位时进分的那只：按第 8 维规则取最差。阵眼是哨兵，一只跌停就是负反馈。 */
    private Integer score;
    /** 空列表 = 那天没有在位的阵眼。一律给可遍历的列表，不给 null。 */
    private List<Item> items = new ArrayList<Item>();

    @Data
    public static class Item {
        private Long id;
        private String code;
        private String name;
        private String role;
        /** 阵眼 / 总龙。 */
        private String roleLabel;
        private String cycleTag;
        private LocalDate startDate;
        /** null = 仍在位。 */
        private LocalDate endDate;
        private String note;
        /** 以下全部现算。取不到行情时 available=false，其余数为 null 而不是 0。 */
        private boolean available;
        private Integer tradeDays;
        private Integer maxBoard;
        private LocalDate lastBreakDate;
        private BigDecimal pct;
        /** 当日最低点涨幅：盘中触板只看它。 */
        private BigDecimal lowPct;
        /** 距跨度最高收盘回撤%，≤0。 */
        private BigDecimal drawdownPct;
        /** 判涨跌停用的阈值%，界面上要说清这只票按几个点判。 */
        private BigDecimal limitPct;
        private Boolean closeLimitDown;
        private Boolean touchedLimitDown;
        private Boolean brokeToday;
        private Boolean newSpanHigh;
        private Integer score;
        /** 中文依据串，如「哈药股份 盘中触板 -7.47%」。 */
        private String reason;
    }

    /** 曲线画跨度区间用：只给起止和最高板，不拉当日行情。 */
    @Data
    public static class Span {
        private Long id;
        private String code;
        private String name;
        private String role;
        private String roleLabel;
        private LocalDate startDate;
        private LocalDate endDate;
        /**
         * 起止日截到请求窗口内的那天，给曲线画色块用。
         * 注册原样（startDate/endDate）不能直接落进 x 轴：窗口外那一截既画不出来，
         * 而轴上的 MM/dd 又反推不出年份，与其让前端猜不如后端一次算清。
         */
        private LocalDate chartFrom;
        private LocalDate chartTo;
        private Integer tradeDays;
        private Integer maxBoard;
    }

    /**
     * 曲线第二根轴用的一天：在位阵眼里<b>最差</b>那只的当日涨跌与第 8 维分。
     *
     * <p>取最差而不是取平均，是因为第 8 维本身就取最差——柱子和分数必须出自同一个判据，
     * 否则图上那根负柱会对不上当天进的分。name 用来在 tooltip 里说清是谁。
     */
    @Data
    public static class Daily {
        private LocalDate date;
        private String code;
        private String name;
        private BigDecimal pct;
        /** 当日最低点涨幅%，盘中触板看它。 */
        private BigDecimal lowPct;
        private Integer score;
    }
}
