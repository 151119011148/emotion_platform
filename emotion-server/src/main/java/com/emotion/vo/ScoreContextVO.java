package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/**
 * 一次「子项读数」：合并维拆开后的每一个子项，各带自动值、算式和进分的那一档。
 *
 * <p>存在的理由是<b>七个数说不清九个分</b>。{@code /snapshot} 只交七个市场字段，
 * 而第 2 维是三组均涨幅加权、第 4 维是三条封板率取平均、第 8/9 维各是一个名单——
 * 他在复盘页看到「已自动填充 5 / 7 项」时，那些合成出来的分下面到底是些什么数，一个字都没有。
 * 这里把它们摊平，算式一律由 {@code TemperatureCalculator} 原样给出，前端不参与定档。
 *
 * <p>刻意<b>不</b>并进 {@code /snapshot}：第 8/9 维要打二三十次行情接口，一次刷新会把
 * {@code market.budget-ms} 顶穿，而他按「拉取行情」只是想要那七个数——上游抖一下就让整个按钮红掉，
 * 是最不该有的失败模式。所以这是另一次请求，自己加载、自己降级、自己一句话。
 *
 * <p>这里只有<b>公开读数</b>：八列 {@code manual_*} 一概不叠。人工值走表单那条路
 * （{@code /api/records}），响应里不带，前端就没有"拉一次行情把手改洗成自动值"的可能。
 */
@Data
public class ScoreContextVO {

    private LocalDate tradeDate;

    // ---------- 第 4 维：两个家数口径子项 ----------

    /**
     * 三池家数原值，两个百分数的分母全部来自这里。
     * 用 Integer 而不是直接把 {@code PoolCounts} 塞进来：那三格为 null 才是"这天没回补明细"，
     * 而实体的 {@code isEmpty()} 会跟着序列化成多出来的一个 "empty" 键。
     */
    private Integer ztCount;
    private Integer zbCount;
    private Integer resealCount;
    private BigDecimal sealedHomeRate;
    private BigDecimal resealRate;
    /** 「家数封板率 39÷(39+48)=44.8% → 0 分」：与落库的 broken_note 里那一句逐字相同。 */
    private String sealedNote;
    private String resealNote;

    // 第 2 维那三组均涨幅不在这里：它早有 /api/market/premium-tiers（只读库、不打网络），
    // 那份 VO 连着逐档、各组分数、权重和"今天这条中位线怎么画的"一起给，这里再拼一遍就是第二个装配器。

    // ---------- 第 8 维 ----------

    /** 阵眼当日反馈分（在位几只取最差）。null = 没设阵眼或那天取不到行情。 */
    private Integer anchorScore;
    /** 进分那一只的原话，卡片 hover 直接显示。 */
    private String anchorNote;

    // ---------- 第 9 维 ----------

    /** 真监管名单的当日进分家数。0 = 拉过了、当天没有进分的票；null = 这天根本没拉过。 */
    private Integer survCount;
    private BigDecimal survPremium;
    private String survNote;

    /** 这次取数实际花了多少毫秒，供界面上那句「最长约 30 秒」有个落点。 */
    private Long elapsedMs;
}
