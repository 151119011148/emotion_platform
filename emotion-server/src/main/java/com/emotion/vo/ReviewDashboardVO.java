package com.emotion.vo;

import com.emotion.entity.IndexClose;
import com.emotion.entity.IndustrySnapshot;
import com.emotion.entity.MarketDaily;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日复盘 /api/review/detail：把 T1-T8 落库后的原始数据 + 计算指标 + 得分 按 D1-D5 五块聚合返回，
 * 每块带一个就绪度（ok/warn）。前端据此渲染"原始数据 + 计算指标 + 得分 + 就绪度 badge"，
 * 缺什么是块内一眼能看出来的，不再有"空状态"。
 */
@Data
public class ReviewDashboardVO {

    private LocalDate tradeDate;
    /** 该日复盘记录的评分（没有记录时 available=false，维度得分全为空）。 */
    private Score score = new Score();
    private D1 d1 = new D1();
    private D2 d2 = new D2();
    private D3 d3 = new D3();
    private D4 d4 = new D4();
    private D5 d5 = new D5();
    /** 每块就绪度：key = D1..D5，value = 缺什么的提示（ok 时为空串）。 */
    private Map<String, String> readiness = new LinkedHashMap<>();

    @Data
    public static class Score {
        private boolean available;
        private Integer total;
        private BigDecimal temperature;
        private String stage;
        private String stageDirection;
        private Integer forcedEbb;
        private String forcedEbbReason;
        private String signalFlags;
        private Integer scoredDims;
        /** 五维：D1=market D2=theme_main D3=board D4=first D5=high。 */
        private BigDecimal d1;
        private BigDecimal d2;
        private BigDecimal d3;
        private BigDecimal d4;
        private BigDecimal d5;
    }

    /** D1 大盘生态（T 日）：五大指数收盘 + 全市场统计。 */
    @Data
    public static class D1 {
        private List<IndexClose> indexes = new ArrayList<>();
        private int indexFilled;
        private int indexTotal;
        private MarketDaily daily;
    }

    /** D2 日内核心（T 日）：板块分布快照 + 题材热度 TopN。 */
    @Data
    public static class D2 {
        private List<IndustrySnapshot> industries = new ArrayList<>();
        /** 今日题材热度 TopN（按涨停家数降序），依赖题材索引，未建索引时为空。 */
        private List<TopicHeat> topics = new ArrayList<>();
    }

    /** D3 连板生态（T-1→T）：分档溢价。 */
    @Data
    public static class D3 {
        private PremiumTiersVO premiumTiers;
    }

    /** D4 首板生态（T 日）：首板封住/炸板 + 梯队 + 大面/跌停名单。 */
    @Data
    public static class D4 {
        private MarketStocksVO stocks;
    }

    /** D5 高位生态：监管池规模（SEVERE/EXCH 计数）。 */
    @Data
    public static class D5 {
        private int surveillanceCount;
        private int severeCount;
    }
}