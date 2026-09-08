package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 某日的异动监管名单与"监管股今日溢价"。
 *
 * 这里不承诺"从没拉取过"和"今天没有在列的票"可区分——那个区分落在
 * {@code t_daily_record.surv_count} 的 NULL/0 上（见 {@link com.emotion.market.MarketMetrics#survivalPremium}）。
 * 接口只回答"表里现在能推出谁在列、他们今天怎么样"。
 */
@Data
public class SurveillanceVO {

    private LocalDate tradeDate;
    /** 当日<b>进第 9 维</b>的家数（只有 SEVERE/EXCH）。0 = 拉过了、当天没有真监管在列；NULL 落在 surv_count 上。 */
    private int count;
    /** 在列总数，含只发了例行异常波动的票——展示用，不进分母。 */
    private int allCount;
    /** 其中真正取到当日涨跌的家数：小于 count 时均值只代表这部分。 */
    private int matched;
    /** 进分组合的算术平均涨幅%，被脏值守卫（上限按板块 20%/30%）丢掉的算在 dropped 里。 */
    private BigDecimal avgPct;
    private int dropped;
    /** 人话算式，卡片和 tooltip 直接用，不让人猜均值哪来的。 */
    private String note;
    private List<Item> items = new ArrayList<>();

    /** 一只一行；同一只票多起事件合并成一条，events 里逐起列出。 */
    @Data
    public static class Item {
        private String code;
        private String name;
        private BigDecimal pct;
        /** 主要那起的第 k / N 日——剩余天数最多的那起，它才是"为什么今天还在列"。 */
        private int dayIndex;
        private int days;
        private String kind;
        private LocalDate annDate;
        /** 这一只进不进第 9 维：false = 只在名单上（例行异常波动），不参与均值。 */
        private boolean scored;
        /** 「严重异常波动 08/21 第10/10日 + 交易所监管 09/03 第1/10日」这种依据串。 */
        private String describe;
        private List<String> eventLabels = new ArrayList<>();
    }
}
