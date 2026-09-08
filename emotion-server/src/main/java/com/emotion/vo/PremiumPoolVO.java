package com.emotion.vo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 一日档位溢价所需要的取数清单：昨日涨停池里的非首板成员。
 *
 * 存在的理由是"上一交易日"这个判断只留一份。库里没有交易日历表，昨日池是从
 * {@code t_market_stock} 里回看出来的，脚本自己推就会和服务端推成两个答案。
 * 符号也一并给出去：脚本只要照着一列代码去请求，不必再复制一遍代码→市场前缀的规则。
 */
@Data
public class PremiumPoolVO {

    private LocalDate tradeDate;
    /** 这些票都是这一天涨停的，衡量的是它们到 tradeDate 赚不赚钱。 */
    private LocalDate prevTradeDate;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        private String code;
        private String name;
        /** 带市场前缀的行情代码，如 sh600664。 */
        private String symbol;
        private int board;
    }
}
