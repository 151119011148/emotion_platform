package com.emotion.vo;

import lombok.Data;

/**
 * D2 题材热度单项：一个题材（概念）在当日涨停池里的聚合热度。
 * 由 {@code t_stock_concept} join 涨停池现算，按涨停家数降序取前 N。
 */
@Data
public class TopicHeat {

    /** 题材/概念名称。 */
    private String name;

    /** 该题材当日涨停个股数（一票多概念会重复计入所属题材）。 */
    private int ztCount;

    /** 该题材内最高连板数。 */
    private int maxBoard;
}