package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 日内核心·题材表 VO。与板块表（行业，一票一行业）相对：题材是概念、一票可归多个题材，
 * 所以计数走 is_primary 主题材去重；题材表独有「催化剂硬度 / 生命周期 / 关联板块」三列。
 */
@Data
public class IntradayVO {

    private LocalDate date;
    /** 当日全市场涨停家数（板块表合计，可加总）。 */
    private int totalZt;
    /** 已归类到至少一个题材（主题材）的独立股票数。 */
    private int assigned;
    /** 未归类独立股票数 = totalZt - assigned。 */
    private int unassigned;
    private List<ThemeRow> themes = new ArrayList<>();

    @Data
    public static class ThemeRow {
        private int rank;
        private Long themeId;
        private String name;
        private int ztCount;
        private int maxBoard;
        private BigDecimal sealSum;
        private int yiziCnt;
        private int bigLossCnt;
        private int continuousDays;
        private boolean mainLine;
        private Integer hardness;
        private String status;
        private String tierLevels;
        private double strength;
        /** 该题材横跨的行业板块（去重，体现"跨行业"价值）。 */
        private List<String> industries = new ArrayList<>();
        private Leader leader;
    }

    @Data
    public static class Leader {
        private String code;
        private String name;
        private int board;
        private BigDecimal changePct;
    }
}