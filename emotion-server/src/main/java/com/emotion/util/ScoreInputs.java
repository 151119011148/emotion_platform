package com.emotion.util;

import java.math.BigDecimal;

import com.emotion.market.MarketMetrics;
import com.emotion.market.PoolCounts;

import lombok.Data;

/**
 * 打分需要、但 {@code t_daily_record} 自己装不下的那部分输入。
 *
 * 独立成一个参数对象而不是往实体上塞字段：档位溢价、阵眼、监管名单都是"每日公开事实"，
 * 而 t_daily_record 是"每个账号一行的判断结果"，混在一起会让一次重算去写别人的行。
 *
 * 每一项都是 null=没取到（该维未评、整维剔出分母），不是 0 分。
 */
@Data
public class ScoreInputs {

    /** 昨日涨停池按连板档拆开的溢价；null 表示这一天没有非首板样本或根本没回补。 */
    private MarketMetrics.PremiumTiers premiumTiers;

    /**
     * 第 4 维两个家数口径子项的原始输入：一天三池的家数聚合（涨停 / 炸板 / 涨停里开过板的）。
     *
     * 交家数而不是交算好的百分数：封板率、回封率和它们的算式必须出自同一次计算，
     * 拆成两个字段传就会有两个能各自漂移的数。单测也因此能直接喂一组家数。
     * null 或空 = 那天没有盘面明细，两个家数子项都未评，这一维退回只看炸板率。
     */
    private PoolCounts poolCounts;

    /** 第 8 维：在位阵眼按"取最差"合成后的分数。null = 没设阵眼 / 那天没有行情 / 请求失败。 */
    private Integer anchorScore;
    /** 中文依据串，卡片直接显示：0 分是撞了哪一条判据必须看得见。 */
    private String anchorNote;

    /**
     * 第 9 维：真监管（SEVERE/EXCH）名单的当日进分家数。
     * 0 = 拉过了、当天确实没有进分的监管股；null = 这一天根本没拉过。
     * 两者都不进分母，但界面上是两句话，所以这里必须是 Integer 而不是 int。
     */
    private Integer survCount;
    /** 进分组合的当日算术平均涨幅%；家数>0 但一只都没取到涨跌时也是 null。 */
    private BigDecimal survPremium;
    private String survNote;

    public static ScoreInputs empty() {
        return new ScoreInputs();
    }
}
