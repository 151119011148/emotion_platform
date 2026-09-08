package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 连板档位的"昨日涨停股今日溢价"，一日一档一行。
 *
 * 首板不入表（使用者的口径），8 表示 8 及以上。整池一个标量看不出钱在哪个档赚钱，
 * 而高位抱团 / 高低切 / 中位负反馈吹哨这三种盘面在标量上会是同一个数。
 * 公开行情数据，不绑 user_id。
 */
@Data
@TableName("t_premium_tier")
public class PremiumTier {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 当日：衡量的是"昨日涨停池"今天赚不赚钱。 */
    private LocalDate tradeDate;
    /** 昨日连板数 2..8，8=8 及以上。 */
    private Integer board;
    /** LOW=2-3 / MID=4-5 / HIGH=6+，见 {@link com.emotion.market.PremiumGroup}。 */
    private String groupKey;
    /** 该档家数，取自昨日涨停池。 */
    private Integer stockCount;
    /** 真正取到当日涨跌的家数。小于 stock_count 说明有股没拉到价，均值只代表这部分。 */
    private Integer matched;
    /** null = 该档无可用样本（未评），不是 0 分。 */
    private BigDecimal avgPct;
    private BigDecimal maxPct;
    private BigDecimal minPct;
    private LocalDateTime createdAt;
}
