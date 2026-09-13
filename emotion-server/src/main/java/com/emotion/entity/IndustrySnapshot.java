package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 行业板块单日聚合快照（T5）：由当日涨停池的每只票按 {@code industry} 现算，无外部数据源。
 *
 * <p>公开行情数据、不绑用户，与 {@code t_market_stock} 同一族。封单/一字/大面/覆盖层都是
 * "某天板块内涨停股"当日的客观事实，落这一张表是为了复盘页 D2 主线明确度一眼看板块分布，
 * 不必每次在复盘页现场对涨停池跑一遍 GROUP BY。
 */
@Data
@TableName("t_industry_daily_snapshot")
public class IndustrySnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate tradeDate;
    /** 行业板块（上游 hybk），空串不参与聚合。 */
    private String industry;
    /** 板块涨停家数。 */
    private Integer ztCount;
    /** 板块最高连板数。 */
    private Integer maxBoard;
    /** 封单总额（元），涨停池 seal_amount 求和。 */
    private BigDecimal sealSum;
    /** 一字板家数：首封 <= 09:30:00 且未开板（break_count=0）。 */
    private Integer yiziCnt;
    /** 板块大面家数：炸板池里回撤>7%且收绿的该板块票数。 */
    private Integer bigLossCnt;
    /** 覆盖层：该板块涨停股覆盖到的连板层，如 "4,3,2"。 */
    private String tierLevels;
}