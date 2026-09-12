package com.emotion.vo;

import java.time.LocalDate;

import lombok.Data;

/**
 * 昨涨停股今日逐只表现（含首板）回补结果。total/matched 必须并排给：
 * matched 少于 total 时 1 进 2 大面仍是下界，不能让脚本误以为全覆盖了。
 */
@Data
public class ZtPerfVO {

    private LocalDate tradeDate;
    /** 昨日涨停池日期（分母日）。 */
    private LocalDate prevTradeDate;
    /** 昨日涨停池家数（分母）。 */
    private Integer total;
    /** 实际写入逐只涨跌的家数。 */
    private Integer matched;
    /** 其中昨日首板家数（1 进 2 口径的分母人群）。 */
    private Integer prevFirstCount;
    /** QUOTE / KBAR / BK。 */
    private String source;
}
