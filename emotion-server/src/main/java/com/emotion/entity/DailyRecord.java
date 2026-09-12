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

    // ==================== 客观行情九数（2026-09-11 起已隔离到 t_market_daily，全局共享、不绑用户）====================
    // 这里保留同名字段只作<b>瞬态承载</b>（exist=false，不参与 t_daily_record 的增删改查）：
    //   · 写：/snapshot、表单保存、md 导入一律先进 MarketDailyStore；
    //   · 读：Service 出参前把 t_market_daily 当天行合并进来，前端 JSON 契约逐字不变；
    //   · 打分：scoreAndPlace 前从客观日表回填，TemperatureCalculator / LadderMetricsService 照旧从 carrier 读数。
    // 绝不能在别处给它们加回表字段：那会重新把"每日公开事实"复制成按账号的 N 份。
    @TableField(exist = false)
    private Integer maxConsecutiveLimit;
    @TableField(exist = false)
    private Integer limitUpCount;
    @TableField(exist = false)
    private Integer limitDownCount;
    @TableField(exist = false)
    private BigDecimal yesterdayLimitPremium;
    @TableField(exist = false)
    private BigDecimal brokenBoardRate;
    @TableField(exist = false)
    private Integer bigLossCount;
    @TableField(exist = false)
    private BigDecimal totalVolume;

    /**
     * 全市场涨跌家数（瞬态，权威表是 t_market_daily）。五维 D1·广度(red_ratio)用它，
     * 旧九维第 3 维用的是涨停 vs 跌停家数，这两个数在旧口径里只并排看广度。
     */
    @TableField(exist = false)
    private Integer upCount;
    @TableField(exist = false)
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

    // ==================== 五维双层模型（five_dim）====================
    // 0-100 直加权：每维分 0-100，总分=Σ(维分×维权)；未评（NULL）整维剔出分母。
    // ALWAYS 与旧 9 维 score_* 同：recalc 后"未评"必须真的写回 NULL，否则界面会挂着上一版没消的旧分。
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal scoreMarket;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal scoreThemeMain;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal scoreBoard;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal scoreFirst;
    /** 旧 D5（龙头分工，v2）维分：2026-09-12 D5 融合后冻结留痕，不再写新值；现行第 5 维是 {@link #scoreHigh}。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal scoreAnchor;
    /** 五维·高位生态（阵眼个体35+抱团资金30+监管压制20+监管反馈15，five_dim_v2 v3 融合版）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal scoreHigh;

    /** 结构信号 CSV："中位吹哨,高位抱团,抱团瓦解前兆,高低切,全面退潮" 子集，可多选。null=无信号或未算。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String signalFlags;
    /** 强制退潮（0/1）：任一命中即出"退潮(强制)"，无视 total_score。null=未算。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer forcedEbb;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String forcedEbbReason;

    /**
     * 五维模型下的纯人工子指标 9 列（NULL=未填=这一子不进分；0 是真实读数不是没填）：
     * 板块涨停数 / 板块溢价 / 梯队完整性 / 持续性天数 / 极高位换手 / 首板溢价 /
     * 首板封板率 / 极高位是否爆量断板 / 阵眼监管折扣。
     * 前四项是 theme_main 维的人工子，中三项是 first 维与 forced-ebb cond 4 的读数，
     * 后两项是 D5 阵眼的折扣乘数与 cond 4 的闸门。
     *
     * <p>这九条一律没有任何自动生产者：{@code LadderMetricsService.aggregate} 取不到它们
     * （炸板池分不出首板、极高位断板要人眼看、监管折扣是主观乘数），所以 {@code metrics} 里
     * 对应的键只有靠这几列才有值——缺列的现场表现是 D2 整维恒未评、cond 4 恒不触发，
     * 看着像引擎漏了判定，其实是取数口就没开。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer manualSectorLimitUpCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualSectorPremiumPct;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualLadderCompleteScore;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer manualThemePersistenceDays;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualTopHighTurnoverPct;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualFirstPremiumPct;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualFirstSealedRate;
    /** 极高位是否爆量断板未回封：1=是。引擎按 {@code isOne} 判，null=未判=cond 4 不参与。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer manualTopHighBreak;
    /** 阵眼监管折扣乘数(0-1)：null=不打折。刻意不走 overlayDecimal 那句 % 文案。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualAnchorSupervisionDiscount;
    /**
     * @deprecated 旧两市口径（主线板块成交额/两市成交额），已停用：D2 成交额聚集度固定按涨停股口径
     * 自动计算（主线涨停股 amount/全部涨停股 amount），列保留仅为兼容历史写入，不再进分。
     */
    @Deprecated
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal manualAmountGatherPct;

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
     * <b>已停用</b>：各节判断文字（复盘页那块编辑口 + 导出回填）这一整套已经下线，
     * 页面不再写它，导出也一律留 {@code ✍️ 判断} 占位让他直接写在 md 里。
     * 列和历史值都原样留着，但<b>不要</b>在没有新需求的情况下把它接回任何一条读写链路。
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
