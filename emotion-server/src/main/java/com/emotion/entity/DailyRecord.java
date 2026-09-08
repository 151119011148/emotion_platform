package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_daily_record")
public class DailyRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate tradeDate;

    // 六维原始指标
    private Integer maxConsecutiveLimit;
    private Integer limitUpCount;
    private Integer limitDownCount;
    private BigDecimal yesterdayLimitPremium;
    private BigDecimal brokenBoardRate;
    private Integer bigLossCount;
    private BigDecimal totalVolume;

    /**
     * 全市场涨跌家数，复盘 md 导入。<b>不进分母</b>：03 篇把涨跌家数比列在「辅助指标（选看）」，
     * 第 3 维用的是涨停 vs 跌停家数。这两个数只用来并排看广度，改判得先动 03 篇。
     * ALWAYS 是为了导入器能表达"键写了空值 = 置回未填"，默认的 NOT_NULL 会跳过 null。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer upCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer downCount;

    // 打分维 1-7：null = 该维未评（原始字段缺失或无历史），已整维剔出分母。
    // 第 8 维（阵眼）在第 8/9 维那一块，第 9 维（监管股溢价）同理。
    // ALWAYS 是必须的——MP 默认 update 策略 NOT_NULL 会跳过 null，导致"置回未评"永远写不进去。
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer scoreHeight;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer scorePremium;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer scoreBreadth;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer scoreBroken;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer scoreLoss;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer scoreVolume;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer scoreTheme;

    // 汇总
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer totalScore;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal temperature;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal prevTemperature;
    /** 本次真的参与打分的维数（0-9）。少于 5 维不出阶段。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer scoredDims;

    // 溢价拆层与三个新增派生块。同样全部 ALWAYS：重算时这些列会从"有值"回到"未评"，
    // 漏一个注解就等于那一天永远清不回 NULL，界面上会一直挂着上一轮的旧依据串。
    /** 三组档位溢价按权重合成出来的 %，只为追溯，不进分；进分的是 scorePremium。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal premiumWeighted;
    /** 第 8 维：周期阵眼当日反馈。null = 未设阵眼或那天没行情。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer anchorScore;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String anchorNote;
    /** 第 9 维人群：当日处于异动监管期的家数。0 = 拉过了确实没有，null = 从没拉取，两者含义不同。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer survCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal survPremium;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String survNote;
    /**
     * 第 4 维的两个新增子项：家数封板率 = 涨停 ÷（涨停 + 炸板），
     * 回封率 = 封住前曾打开的涨停 ÷（那些 + 炸板）。家数取自盘面明细，不打上游。
     * null = 那天没有明细，这一维退回只看炸板率，不是 0 分。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal sealedHomeRate;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal resealRate;
    /** 三个子项各自的算式与出分，中文串。炸板率是次数口径、封板率是家数口径，不写出来会被读成同一个数。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String brokenNote;

    /**
     * 子项人工覆盖八列：第 2 维三组均涨幅、第 4 维两条家数口径子项、第 8 维的分、第 9 维的家数与均值。
     *
     * <p>一律 {@code NULL = 这格没改}，不是 0 分：0% 封板率和 0 家进分都是能把阶段判翻的真实读数。
     * 覆盖只落在自己这一行——{@code t_premium_tier} 与 {@code t_market_stock} 都没有 user_id，
     * 是"每日公开事实"，人工值写进那两张表就等于替所有账号改了同一份事实。
     *
     * <p>ALWAYS 在这里不是模板照抄：清空一格的语义就是"退回自动值"，
     * 默认的 NOT_NULL 会让那个 0 分永远赖在温度里不走。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualSealedHomeRate;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualResealRate;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualPremiumLowPct;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualPremiumMidPct;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualPremiumHighPct;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer manualAnchorScore;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer manualSurvCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualSurvPremium;

    // 阶段定位
    private String stage;
    private String stageDirection;
    private Integer stageOverridden;
    /** 同一主阶段的第几个回合：退潮→反弹→退潮 会打出 一阶段/反弹一阶段/二阶段。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer stageSeq;
    /** 子段标签，目前只有 "反弹" 一种；空串=常规段。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String stagePhase;

    // 主线龙头
    private String mainTheme;
    private String leadingStock;
    private String leadingStockStatus;
    private String midCapStock;

    /** 我自己的实际仓位%。市场读数是公开的，这一格只有你知道；平台不猜，也不进分。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal myPositionPct;

    // 复盘文本
    private String rotationNote;
    private String reviewNote;
    private String tomorrowPlan;
    /** 整篇复盘原文，含 ```meta 围栏块本身——md 是唯一真相，库里这份要能原样导回编辑器改完再导入。 */
    private String reviewMd;
    /**
     * 各节判断文字的 JSON 原文，键见 {@code ReviewDocFormatter.NOTE_KEYS}。
     * 只有「导出复盘文档」那条只读链路会用它，打分和 md 导入契约都不认它。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String docNotes;
    /**
     * 手记读数与系统读数的对照原文。md 的 {@code 对照:} 键和复盘页那一格都写它，
     * 读的时候列优先、{@code review_md} 兜底（那两天的老值只在原文里）。
     * ALWAYS：表单里删空这格，得真的回到 NULL，不能留着上一版的字。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String compareNote;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
