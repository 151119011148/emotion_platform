package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 五大指数收盘：复盘 md 里一条 {@code 指数:} 一行。
 *
 * <p>公开数据、不绑 user_id，和 {@code t_market_stock} 同一族。指数滞涨 vs 个股普跌这种背离，
 * 只有收盘价看得出来——九维里只有两市成交额那一个量能读数，看不出结构。
 */
@Data
@TableName("t_index_close")
public class IndexClose {

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate tradeDate;
    /** 不带市场前缀的指数代码，如 000001 / 399006 / 899050。 */
    private String indexCode;
    private String indexName;
    private BigDecimal closePrice;
    private BigDecimal changePct;
    private LocalDateTime createdAt;
}
