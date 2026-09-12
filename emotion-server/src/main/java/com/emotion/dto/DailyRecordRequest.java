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
     * 旧九维的子项人工覆盖（第 2 维三组均涨幅、第 4 维两条家数口径子项、第 8 维的分、第 9 维的家数与均值）。
     *
     * <p>五维模型上线后这一组只有 {@code TemperatureCalculator} 那条旧口径展示链还在读，复盘页的格子已下线。
     * 键与列都保留：删格子不会洗掉已存值（页面不发键 = present 守卫不命中），留着历史可追。
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

    /**
     * 五维打分里那些「盘面没有可回补取数口径」的人工读数，一列一格，喂给
     * {@code ScoreContextService.applyManualMetrics} 覆盖同名 metric。
     *
     * <p>D2 主线明确度那四格（板块涨停数 / 梯队完整性 / 板块溢价 / 持续性天数）是整维纯人工，
     * 一个都不填则 D2 恒未评；D4 的首板溢价与首板封板率、D5 的监管折扣、
     * 强制退潮条件 4 的极高位换手与断板同理。刻度 0-100 的直接是分，带 % 的是百分数，
     * {@code manualAnchorSupervisionDiscount} 是 0-1 的乘数不是百分数。
     *
     * <p>与上一组同一个规矩：<b>发 null = 这格清回未填</b>（该 metric 退回自动值或未评），
     * <b>不发键 = 这格不动</b>。所以这九个键必须与前端 {@code FORM_OWNED_KEYS} 同进同出。
     */
    private Integer manualSectorLimitUpCount;
    private BigDecimal manualLadderCompleteScore;
    private BigDecimal manualSectorPremiumPct;
    private Integer manualThemePersistenceDays;
    private BigDecimal manualTopHighTurnoverPct;
    private BigDecimal manualFirstPremiumPct;
    private BigDecimal manualFirstSealedRate;
    /** 极高位是否爆量断板未回封：1=是 0=否。null=未判=强制退潮条件 4 不参与。 */
    private Integer manualTopHighBreak;
    private BigDecimal manualAnchorSupervisionDiscount;
    /** @deprecated 旧两市口径人工列，已不再影响 D2 打分（成交额聚集度固定为涨停股口径自动值），保留仅兼容旧请求。 */
    @Deprecated
    private BigDecimal manualAmountGatherPct;

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
