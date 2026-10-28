package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 个股分钟序列的一根。来源是腾讯 proxy 域（与日 K 同一个已验证主机），一次给一整天。
 *
 * <p>存的是<strong>累计</strong>量与额的原值，不是逐分钟增量：源给的就是累计口径
 * （首根 0930 就带着 42064 手，那是竞价加开盘一分钟的量），差分是读侧一次减法的事，
 * 而存差分要求「上一根一定在库里」——某天缺根就得回头补，落库口径不该有这个前提。
 *
 * <p>价格是不复权的分钟价，只用来描述当天的形态（何时封板、开板多久、量峰在哪），
 * 不参与跨日比较，所以不需要复权因子。
 */
@Data
@TableName("t_stock_minute")
public class StockMinute {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    /** 交易日，取源响应里的 date 字段；与请求当天不符就整票不写。 */
    private LocalDate tradeDate;
    /** HHMM，源给什么存什么（实测 0930…1530）。 */
    private Integer minute;
    private BigDecimal price;
    /** 截至该分钟的累计成交量(手)。 */
    private Long cumVolumeHand;
    /** 截至该分钟的累计成交额(元)。 */
    private BigDecimal cumAmountYuan;
    private LocalDateTime createdAt;
}
