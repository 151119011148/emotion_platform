package com.emotion.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 题材表的下钻详情：梯队（跨行业个股按连板分层）、关联板块（题材→行业映射）、未归类个股。
 */
public class ThemeStockVO {

    /** 题材梯队：按连板分层，体现"跨行业个股聚在一个题材下、按高度排"。 */
    @Data
    public static class Tier {
        private int board;
        private List<StockLine> stocks = new ArrayList<>();
    }

    @Data
    public static class StockLine {
        private String code;
        private String name;
        private String industry;
        private Integer board;
        private BigDecimal changePct;
        private BigDecimal sealAmount;
        private boolean yizi;
        private boolean primary;
    }

    @Data
    public static class IndustryCount {
        private String industry;
        private int count;
    }
}