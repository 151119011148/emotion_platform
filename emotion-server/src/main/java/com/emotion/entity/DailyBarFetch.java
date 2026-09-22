package com.emotion.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次日 K 拉取的覆盖留痕（{@code t_daily_bar_fetch}）。
 *
 * <p>存在的唯一理由：光看 {@code t_daily_bar} 的行数判不出「这段拉过没有」——停牌日天然没有行，
 * 而且是永久状态。要是用「行数够不够交易日数」判命中，停牌股永远判成未缓存，每次照样回源。
 * 所以命中只看这张表：有覆盖记录且在保鲜期内，就信任库里的行；没有就回源。
 */
@Data
@TableName("t_daily_bar_fetch")
public class DailyBarFetch {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String symbol;
    /** 实际请求起点（含 lead 段，保证窗口首日也有前收可比）。 */
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer barCount;
    /** 这次拉取时上游的复权版本号，排查用。 */
    private String fqVersion;
    private LocalDateTime fetchedAt;
}
