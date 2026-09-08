package com.emotion.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 一日盘面个股明细，供仪表盘卡片 hover 展示真实名单。
 *
 * 之所以在后端组好梯队/断档而不是把行丢给前端算：这三样是同一份口径的三个视图，
 * 放在两处算就会有两处能各自错。
 */
@Data
public class MarketStocksVO {

    private LocalDate tradeDate;
    /** 该日明细是否入库过。false 时前端不显示 hover，而不是显示一个空名单。 */
    private boolean available;

    /** 连板梯队，按板数从高到低。只含 2 板以上——首板 60 家塞进 tooltip 没有意义。 */
    private List<Tier> ladder = new ArrayList<>();
    private int firstBoardCount;
    /** 梯队里断掉的板数，如 5 板和 2 板之间的 [3,4]——"独苗"就是这个意思。 */
    private List<Integer> gapBoards = new ArrayList<>();
    private List<Item> bigLoss = new ArrayList<>();
    private List<Item> limitDown = new ArrayList<>();
    /** 明细行数，不是上游 tc：和卡面上的打分字段对照着看，就能看出明细是否回补过。 */
    private int limitUpCount;
    private int limitDownCount;

    @Data
    public static class Tier {
        private int board;
        private List<Item> stocks = new ArrayList<>();
    }

    @Data
    public static class Item {
        private String code;
        private String name;
        private String industry;
        private BigDecimal pct;
        /** 自涨停回撤 %，只有大面行有值。 */
        private BigDecimal pullback;
    }
}
