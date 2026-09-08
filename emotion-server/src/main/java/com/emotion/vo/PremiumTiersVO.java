package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * 一日"昨日涨停池今日溢价"的拆层结果：逐档 2..8+ 与低/中/高三组。
 *
 * available=false 表示这一天没有档位数据（没回补，或昨日池里全是首板），
 * 此时溢价维整维未评——和"算出来是 0%"是两件事，前端文案必须分开。
 */
@Data
public class PremiumTiersVO {

    private LocalDate tradeDate;
    /** 溢价衡量的是哪一天的涨停池在今天的表现。 */
    private LocalDate prevTradeDate;
    private boolean available;
    /** 含首板的整池均值：只做展示，不进分。 */
    private BigDecimal pooledPct;
    /** 三组按 1:1.5:2.5 加权后的合成溢价：只作追溯，进分的是各组分数。 */
    private BigDecimal weightedPct;
    /** 高位抱团 / 高低切 / 中位负反馈吹哨 / 高位断层 / 全面负反馈 / 仅一组 / 无显著结构。 */
    private String structure;
    private int firstBoard;
    private int considered;
    private int matched;
    /** 前一天涨停池最高板：动态中位线的唯一依据。 */
    private int topBoard;
    /** 中位下界 {@code M = round(H/2)}；低 = 2..M-1、中 = M..M+1、高 = ≥M+2。 */
    private int midLine;
    /** 这条界线今天是怎么画出来的，中文一句，卡片 hover 直接显示。 */
    private String binNote;
    /** 逐档 2..8+，<b>按板数从高到低</b>。 */
    private List<Tier> tiers;
    /** 三组，<b>高位在前</b>：读法是"顶端还有没有人赚钱"，不是从低位数上去。 */
    private List<Group> groups;

    /** 逐档：2..8（8=8 及以上）。 */
    @Data
    public static class Tier {
        private int board;
        private String label;
        private String group;
        private int stockCount;
        private int matched;
        private BigDecimal avgPct;
        private BigDecimal maxPct;
        private BigDecimal minPct;
    }

    /** 三组之一：score 是 -1~3（-1=该组当天确认负反馈），avgPct 为 null 时 score 也为 null（该组当天未评，权重摊回其余组）。 */
    @Data
    public static class Group {
        private String group;
        private String label;
        private int stockCount;
        private int matched;
        private BigDecimal avgPct;
        private Integer score;
        private BigDecimal weight;
    }
}
