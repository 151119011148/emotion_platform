package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日盘面个股明细：涨停池/跌停池/炸板池逐只一行。
 *
 * 存的就是"参与聚合计数的那批行"，所以 hover 出来的名单和卡面上的家数永远出自同一次筛选。
 * 公开行情数据，不绑 user_id——多个账号同一天拉到的是同一批行。
 */
@Data
@TableName("t_market_stock")
public class MarketStock {

    /** 涨停池。 */
    public static final String POOL_LIMIT_UP = "ZT";
    /** 跌停池。 */
    public static final String POOL_LIMIT_DOWN = "DT";
    /** 炸板池。 */
    public static final String POOL_BROKEN = "ZB";

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate tradeDate;
    private String code;
    private String name;
    private String pool;
    /** 东财标识：1=沪，0=深（含北）。 */
    private Integer market;
    private String industry;
    /** 连板数，只有涨停池有。 */
    private Integer consecutive;
    private Integer breakCount;
    private BigDecimal changePct;
    private BigDecimal closePrice;
    private BigDecimal limitPrice;
    /** 自涨停回撤 %，只有炸板池有。 */
    private BigDecimal pullbackPct;
    private Integer bigLoss;
    private LocalDateTime createdAt;
}
