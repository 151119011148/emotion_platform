package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 大盘生态页的指数区块（PRD 大盘页 P1）：当日五大指数收盘与涨跌幅。
 *
 * <p>数据来自公开表 {@code t_index_close}（md 导入或 /market/snapshot 日 K 回补），不绑用户。
 * 不传日期时后端回落到最近一个有指数行的交易日，{@code tradeDate} 标明实际命中的日子。
 */
@Data
public class MarketIndexesVO {

    /** 实际命中的交易日；库里一行都没有时 null。 */
    private LocalDate tradeDate;
    private List<Item> indexes = new ArrayList<>();

    @Data
    public static class Item {
        private String code;
        private String name;
        private BigDecimal close;
        private BigDecimal changePct;
    }
}
