package com.emotion.market;

import java.math.BigDecimal;

import lombok.Data;

/** 涨跌停/炸板池里的单只股票，只保留打分真正用到的字段。 */
@Data
public class PoolRow {

    private String code;
    private String name;
    /** 东财的市场标识：1=沪市，0=深市。 */
    private Integer market;
    /** 连板数（涨停池）。 */
    private Integer lbc;
    /** 炸板次数。 */
    private Integer zbc;
    /** 当日涨跌幅 %。 */
    private BigDecimal zdp;
    /** 行业板块（上游 hybk）。三个池都带，是"今天钱在哪个方向"的客观旁证。 */
    private String industry;
    /** 涨停价，上游原始值（×1000）。回撤是比值，量纲会约掉，所以不做换算。 */
    private BigDecimal limitPrice;
    /** 收盘价，上游原始值（×1000）。 */
    private BigDecimal price;
    /** 封单额（元）= 东财 fund，收盘封单资金。 */
    private BigDecimal fund;
    /** 当日成交额（元）= 东财池接口 amount；D2 成交额聚集度（涨停池内资金板块占比）取数源。 */
    private BigDecimal amount;
    /** 首次封板时间 HHMMSS（fbt）。92500=集合竞价封单。 */
    private Integer fbt;
    /** 最后封板时间 HHMMSS（lbt）。 */
    private Integer lbt;

    /**
     * 从涨停价回落的日内回撤幅度 %。
     * 03 篇对"大面"的定义是"从涨停/大涨砸到绿盘，日内回撤 > 7%"，看的是回撤而不是涨跌幅。
     */
    public BigDecimal pullbackFromLimitPct() {
        if (limitPrice == null || price == null
                || limitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return limitPrice.subtract(price)
                .multiply(BigDecimal.valueOf(100))
                .divide(limitPrice, 2, BigDecimal.ROUND_HALF_UP);
    }
}
