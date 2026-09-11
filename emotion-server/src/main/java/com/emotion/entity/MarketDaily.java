package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 全局客观行情日数据：<b>与用户无关、全账号共享</b>，一天一行。
 *
 * <p>这九个字段原本寄生在 {@code t_daily_record}（绑用户）上：它们由 /api/market/snapshot
 * 自动拉取，是"每日公开事实"，存进用户行导致同一份盘面按账号复制、没复盘的日子就没有客观读数。
 * 隔离后主观复盘（人工读数、打分、文本、仓位、阵眼）继续留在 t_daily_record，
 * 这里只放客观九数：连板高度 / 涨停 / 跌停 / 上涨 / 下跌 / 昨涨停溢价 / 炸板率 / 大面 / 成交额。
 *
 * <p>{@link DailyRecord} 上保留同名字段的瞬态承载（{@code @TableField(exist=false)}）：
 * 读接口把本表合并进响应，前端契约不变；打分引擎仍从 carrier 上读数。
 */
@Data
@TableName("t_market_daily")
public class MarketDaily {

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate tradeDate;

    private Integer maxConsecutiveLimit;
    private Integer limitUpCount;
    private Integer limitDownCount;

    /**
     * 全市场涨跌家数。ALWAYS：表单/md 导入允许显式清空（发 null = 清回未填），
     * 其余七列走默认 NOT_NULL 的"非空才 patch"。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer upCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer downCount;

    private BigDecimal yesterdayLimitPremium;
    private BigDecimal brokenBoardRate;
    private Integer bigLossCount;
    private BigDecimal totalVolume;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
