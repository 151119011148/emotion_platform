package com.emotion.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class DailyRecordRequest {
    private LocalDate tradeDate;

    private Integer maxConsecutiveLimit;
    private Integer limitUpCount;
    private Integer limitDownCount;
    private BigDecimal yesterdayLimitPremium;
    private BigDecimal brokenBoardRate;
    private Integer bigLossCount;
    private BigDecimal totalVolume;

    /**
     * 全市场涨/跌家数。<b>不进分母</b>，只是广度读数。
     * 行情源没有可回溯的涨跌家数接口（东财延迟域 f104/f105 实测回 "-"），所以这两格由人填——
     * 现在唯一的写入口是复盘 md 的 {@code 涨跌家数:} 键，复盘页上那块编辑口已经删了。
     *
     * <p>列是 ALWAYS 策略，{@code copyFields} 认的是<b>键在不在场</b>：带这个键（哪怕值是 null）
     * 就是"这格该是这个值 / 清回未填"，不带键 = 一个字都不动。这两种从这个 DTO 上分不出来。
     */
    private Integer upCount;
    private Integer downCount;
    /** 我的实际仓位%。市场读数是公开的，这一格只有你知道；持仓台账没有权重列，推不出它。同上：只由 md 写。 */
    private BigDecimal myPositionPct;

    private Integer scoreTheme;

    /**
     * 子项人工覆盖：第 2 维三组均涨幅、第 4 维两条家数口径子项、第 8 维的分、第 9 维的家数与均值。
     *
     * <p>语义与 {@code upCount} 那三格一样——<b>留空 = 清回未覆盖</b>，退回公开读数算出来的那个值；
     * <b>不发这个键 = 这一格不动</b>（{@code copyFields} 按 {@code DailyRecordController} 传来的键集合判）。
     * 所以这一组键必须和前端 {@code FORM_OWNED_KEYS} 同时成立：没读回这一天的行就不该发这批键，
     * 否则一个半截请求会把已存的人工值一并洗掉。列都是 ALWAYS 策略，0 是覆盖值不是缺省。
     */
    private BigDecimal manualSealedHomeRate;
    private BigDecimal manualResealRate;
    private BigDecimal manualPremiumLowPct;
    private BigDecimal manualPremiumMidPct;
    private BigDecimal manualPremiumHighPct;
    private Integer manualAnchorScore;
    private Integer manualSurvCount;
    private BigDecimal manualSurvPremium;

    private String mainTheme;
    private String leadingStock;
    private String leadingStockStatus;
    private String midCapStock;

    private String rotationNote;
    private String reviewNote;
    private String tomorrowPlan;
    /**
     * 手记读数对照。语义与 {@code upCount} 那几格一样：<b>留空 = 清回未填</b>（列挂 ALWAYS），
     * 所以它必须进前端 {@code FORM_OWNED_KEYS} —— 没读回这天的行就发这一格，等于把他存的对照洗掉。
     */
    private String compareNote;

    private Integer stageOverridden;
    private String stage;
}
