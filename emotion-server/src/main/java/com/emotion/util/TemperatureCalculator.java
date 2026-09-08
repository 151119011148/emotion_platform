package com.emotion.util;

import com.emotion.entity.DailyRecord;
import com.emotion.market.AnchorMetrics;
import com.emotion.market.MarketMetrics;
import com.emotion.market.PoolCounts;
import com.emotion.market.PremiumGroup;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * 九维打分与阶段判定。口径严格照知识库 03 篇的打分表，原文没写的档位一律标注出来。
 *
 * <p>每维取值 -1~3，分母仍是 {@code 3 × 已评维数}，所以温度区间从 0~100 变成 -33.3~100。
 * 阶段线 15/35/55/80 一个都没跟着挪——挪线等于同时改三个新旋钮，那样出来的对照表没法读。
 * 后果和缺维一样落在绝对值上：看趋势别看水位。
 *
 * 五条和常见实现相反的规则，都是刻意的：
 * 1. 评不了的维度返回 null 并整维剔出分母。按 0 分计入等于把"今天没填"读成"今天确认极差"，
 *    一个未填的主线明确度就能凭空压低 11.1°（九维 27 分制）。
 *    后果写明白：第 8/9 两维天生经常缺席（没设阵眼、当天没有在列监管股），所以参与维数会随
 *    账号和日子变，温度<b>绝对值</b>的可比性因此下降，Δ 的可比性不变——看趋势别看水位。
 * 2. 阶段判定变化率优先。02 篇里 分歧("60± 波动")/退潮("40→10") 是运动状态而非水位，
 *    先判静态档位会把 (0,100) 铺满，这两个阶段永远不可达。
 * 3. 溢价维看的是分档合成，不是含首板的整池均值。整池一个标量会把"高位抱团"和
 *    "中位负反馈吹哨"读成同一个数；yesterday_limit_premium 仍然展示，但不再进分。
 * 4. 负档只开在"原文最低档是个大桶"的维度上（溢价、涨跌停比、大面、连板高度、炸板维），
 *    用来把"差"和"崩了"分开；量能、主线、阵眼三维的 0 档本身已经判到最重，不再往下加。
 * 5. 溢价低/中/高的界线跟着当天最高板走（{@code M=round(H/2)}），不写死 2-3/4-5/6+。
 *    顶端在哪、高位就在哪，否则一轮小周期里"高位"这个词是空的。
 */
public class TemperatureCalculator {

    /** 每维满分，也是 03 篇打分表里的"满分 3"。分母用它，九维全满仍是 100°。 */
    public static final int DIM_MAX = 3;
    /**
     * 每维下限。-1 = "确认负反馈/崩了"，和 03 篇最低档的"差"隔开一档。
     *
     * <p>分母刻意继续用 {@link #DIM_MAX} 而不是改成 3-(-1)=4：-1 是少数日子才踩得到的档，
     * 把分母撑大等于把所有正常日子的读数整体往上抬一截，那是把整把尺子重刻，不是加一档。
     */
    public static final int DIM_MIN = -1;
    /**
     * 少于这个维数只出温度、不出阶段。
     *
     * <p>九维分母下它的含义已经从"最多缺 2 维"变成"最多缺 4 维"——数字没跟着动是刻意的，
     * 第 8/9 两维天生经常缺席（没设阵眼、当天没有在列监管股），调高它等于让这两维缺席的日子集体失去阶段。
     * 回补对照表里如果看到大量 5~6 维的日子，再回来调。
     */
    public static final int MIN_DIMS_FOR_STAGE = 5;
    /** 判定转弱的温差：九维 27 分制下 = 3.24 个得分点，也就是"跳两档以上才算动"。 */
    private static final double DROP_THRESHOLD = 12;
    /** 退潮与分歧的分界，对齐 02 篇 退潮="40→10"。 */
    private static final double TIDE_LINE = 40;
    /**
     * 发酵线：02 篇里 发酵/启动 的分档下界。 {@code CycleStageMachine} 判反弹时拿它当上限
     * ——涨回这条线以上的日子主阶段自己就变 发酵 了，那是反转不是反弹，两处必须同一个数。
     */
    public static final double FERMENT_LINE = 55;

    /**
     * 档位权重：高位给得更重（使用者原话"高位的权重稍微重一些"）。
     * 这是溢价维唯一的拍板数，改这一处即可，结构信号那边跟着用同一组。
     */
    public static final double W_LOW = 1.0;
    public static final double W_MID = 1.5;
    public static final double W_HIGH = 2.5;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static void calculate(DailyRecord record, List<DailyRecord> recentRecords) {
        calculate(record, recentRecords, ScoreInputs.empty());
    }

    public static void calculate(DailyRecord record, List<DailyRecord> recentRecords, ScoreInputs inputs) {
        ScoreInputs in = inputs == null ? ScoreInputs.empty() : inputs;
        Integer height = calcHeightScore(record, recentRecords);
        Integer premium = calcPremiumScore(record, in);
        Integer breadth = calcBreadthScore(record);
        BrokenDim brokenDim = calcBrokenDim(record, in);
        Integer broken = brokenDim.getScore();
        Integer loss = calcLossScore(record);
        Integer volume = calcVolumeScore(record, recentRecords);
        Integer theme = calcThemeScore(record);
        Integer anchor = in.getAnchorScore();
        Integer survival = calcSurvivalScore(in.getSurvCount(), in.getSurvPremium());

        record.setScoreHeight(height);
        record.setScorePremium(premium);
        record.setScoreBreadth(breadth);
        record.setScoreBroken(broken);
        record.setSealedHomeRate(brokenDim.getSealedHomeRate());
        record.setResealRate(brokenDim.getResealRate());
        record.setBrokenNote(brokenDim.getNote());
        record.setScoreLoss(loss);
        record.setScoreVolume(volume);
        record.setScoreTheme(theme);
        record.setAnchorScore(anchor);
        record.setAnchorNote(in.getAnchorNote());
        record.setSurvCount(in.getSurvCount());
        record.setSurvPremium(in.getSurvPremium());
        record.setSurvNote(in.getSurvNote());
        record.setPremiumWeighted(compositePremiumPct(record, in));

        List<Integer> dims = Arrays.asList(height, premium, breadth, broken, loss, volume, theme,
                anchor, survival);
        int scored = 0;
        int sum = 0;
        for (Integer dim : dims) {
            if (dim != null) {
                scored++;
                sum += dim;
            }
        }

        record.setScoredDims(scored);
        record.setTotalScore(scored == 0 ? null : sum);
        record.setTemperature(scored == 0 ? null
                : BigDecimal.valueOf(sum).multiply(HUNDRED)
                        .divide(BigDecimal.valueOf((long) DIM_MAX * scored), 1, RoundingMode.HALF_UP));
    }

    /** 连板高度趋势：上升/维持高位=3, 横盘=2, 下降=1, 断龙=0。 */
    static Integer calcHeightScore(DailyRecord record, List<DailyRecord> recent) {
        Integer h = record.getMaxConsecutiveLimit();
        if (h == null) {
            return null;
        }
        boolean hasHistory = recent != null && recent.size() >= 2;
        int prevMax = 0;
        if (hasHistory) {
            for (DailyRecord past : recent) {
                Integer v = past.getMaxConsecutiveLimit();
                if (v != null && v > prevMax) {
                    prevMax = v;
                }
            }
        }
        if (h <= 1) {
            // 原文"断龙=0"是一整块。4 板以上直接塌回首板，和本来就在 2~3 板打转的池子塌到首板
            // 不是同一个量级——前者是龙头腰斩的确认信号，后者只是低位循环，分开才判得出来。
            return prevMax >= 4 ? DIM_MIN : 0;
        }
        if (!hasHistory) {
            return null;
        }

        if (h > prevMax) {
            return 3;
        }
        if (h == prevMax && h >= 4) {
            return 3;
        }
        if (h >= 3) {
            return 2;
        }
        return 1;
    }

    /**
     * 昨日涨停溢价（分档合成）：低/中/高三组各自按 03 篇阈值出 -1~3，再按 1 : 1.5 : 2.5 加权。
     *
     * 缺档的权重是整块摊回其余在场档位，不是按 0 分计入分母：高位那天没有票，
     * 不等于"高位确认极差"，更不等于可以把唯一的负反馈信号平均掉。
     * 三组全空 → null（未评）。
     *
     * <p>取整走 {@link #average} 那条 BigDecimal HALF_UP（远离 0）的路：
     * Math.round(-0.5)=0 会把已经合成成负的读数抬回 0，等于负档在最常触发的一维上白开。
     */
    static Integer calcPremiumScore(DailyRecord record, ScoreInputs in) {
        return calcPremiumScore(premiumPctOf(record, in == null ? null : in.getPremiumTiers()));
    }

    static Integer calcPremiumScore(MarketMetrics.PremiumTiers tiers) {
        return calcPremiumScore(premiumPctOf(null, tiers));
    }

    /**
     * 「按组取均涨幅」收成一层薄壳：出分与追溯共用同一个取法。
     *
     * <p>刻意做成函数而不是先算好三个数传进来：出分（加权分数）和追溯（加权百分数）必须读同一组数，
     * 传两个 BigDecimal 就会有一个忘了叠人工值，界面上表现为"卡面 3 分、合成溢价却还是自动那个数"。
     */
    private static Function<PremiumGroup, BigDecimal> premiumPctOf(DailyRecord record,
                                                                   MarketMetrics.PremiumTiers tiers) {
        return group -> effectivePremiumPct(record, tiers, group);
    }

    /** 某一组真正进分的均涨幅：这一行手改了就用人工值，否则用档位表聚合出来的那个。 */
    private static BigDecimal effectivePremiumPct(DailyRecord record, MarketMetrics.PremiumTiers tiers,
                                                  PremiumGroup group) {
        BigDecimal auto = tiers == null ? null : tiers.group(group).getAvgPct();
        return ManualOverride.pick(manualPremiumPct(record, group), auto);
    }

    private static BigDecimal manualPremiumPct(DailyRecord record, PremiumGroup group) {
        if (record == null) {
            return null;
        }
        switch (group) {
            case LOW:
                return record.getManualPremiumLowPct();
            case MID:
                return record.getManualPremiumMidPct();
            default:
                return record.getManualPremiumHighPct();
        }
    }

    private static Integer calcPremiumScore(Function<PremiumGroup, BigDecimal> pctOf) {
        double weightSum = 0;
        double scoreSum = 0;
        for (PremiumGroup group : PremiumGroup.values()) {
            BigDecimal avg = pctOf.apply(group);
            if (avg == null) {
                continue;
            }
            double weight = weight(group);
            weightSum += weight;
            scoreSum += weight * bandPremium(avg);
        }
        if (weightSum <= 0) {
            return null;
        }
        return BigDecimal.valueOf(scoreSum / weightSum)
                .setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * 合成溢价 %：同样按档位权重加权，但加权的是涨幅本身而不是分数。
     * 只为追溯和 tooltip 服务，不进分——数值型会被单只 +20% 绑架，先分档再加权才压得住。
     *
     * <p>带 record 的那个入口是打分用的（与 {@code scorePremium} 同源，人工值一并算进来）；
     * 只吃 tiers 的那个是公开只读视图用的，那里没有"谁的这一行"可言。
     */
    public static BigDecimal compositePremiumPct(MarketMetrics.PremiumTiers tiers) {
        return compositePremiumPct(premiumPctOf(null, tiers));
    }

    static BigDecimal compositePremiumPct(DailyRecord record, ScoreInputs in) {
        return compositePremiumPct(premiumPctOf(record, in == null ? null : in.getPremiumTiers()));
    }

    private static BigDecimal compositePremiumPct(Function<PremiumGroup, BigDecimal> pctOf) {
        double weightSum = 0;
        double pctSum = 0;
        for (PremiumGroup group : PremiumGroup.values()) {
            BigDecimal avg = pctOf.apply(group);
            if (avg == null) {
                continue;
            }
            double weight = weight(group);
            weightSum += weight;
            pctSum += weight * avg.doubleValue();
        }
        if (weightSum <= 0) {
            return null;
        }
        return BigDecimal.valueOf(pctSum / weightSum).setScale(2, RoundingMode.HALF_UP);
    }

    /** 03 篇溢价四档：&gt;+4%=3, 0~+4%=2, -2%~0=1, &lt;-2%=0；实测把最低档再切一刀：&lt;-4%=-1。第 9 维「监管股今日溢价」复用同一组阈值。 */
    static int bandPremium(BigDecimal pct) {
        double v = pct.doubleValue();
        if (v > 4) {
            return 3;
        }
        if (v > 0) {
            return 2;
        }
        if (v >= -2) {
            return 1;
        }
        // 原文"-2% 以下"是一整块，跨度却从 -3%（当天只是不好看）到 -10.7%（池子被核按钮）。
        // 高位组权重 2.5，整块记 0 等于把崩塌和难看读成同一个数。
        return v >= -4 ? 0 : DIM_MIN;
    }

    public static double weight(PremiumGroup group) {
        switch (group) {
            case LOW:
                return W_LOW;
            case MID:
                return W_MID;
            default:
                return W_HIGH;
        }
    }

    /**
     * 结构信号：钱在高位抱团、在高低切、还是中位已经先出问题。只读展示，不进分（避免双计）。
     * 判据用的是三组分数（已含权重），阈值是第二个可调旋钮。
     */
    public static String premiumStructure(MarketMetrics.PremiumTiers tiers) {
        Integer low = scoreGroup(tiers, PremiumGroup.LOW);
        Integer mid = scoreGroup(tiers, PremiumGroup.MID);
        Integer high = scoreGroup(tiers, PremiumGroup.HIGH);
        int present = 0;
        for (Integer score : Arrays.asList(low, mid, high)) {
            if (score != null) {
                present++;
            }
        }
        if (present < 2) {
            return present == 0 ? "无样本" : "仅一组";
        }
        if (isLowOrZero(low) && isLowOrZero(mid) && isLowOrZero(high)) {
            return "全面负反馈";
        }
        // 高位断层：顶端不在场、低位也赚不到钱——链条是从最高一级先断的。
        // 分档改动态之后这一档基本废了：H≥4 时最高板自己就落在高位组，"顶端一只都不在场"这个
        // 市场形态再也表达不出来，只剩"顶端有票却一只价都没取到"这种数据缺口会走进来。
        // 原先它是 14 天里 10 天的形态，靠写死 6+ 才看得见。判据要不要跟着改等你定，这里不擅自删
        // （现状由 TemperatureCalculatorTest#absentHighTierWithWeakLowIsAFaultLine 钉住）。
        if (high == null && low != null && low <= 1) {
            return "高位断层";
        }
        // 中位吹哨：高位还在赚钱，但接力的中段先亏钱了——这轮周期最常见的顶部形态
        if (high != null && high >= 2 && mid != null && mid <= 1) {
            return "中位负反馈吹哨";
        }
        if (high != null && low != null && low - high >= 2 && high <= 1) {
            return "高低切";
        }
        if (high != null && high >= 2 && low != null && high > low) {
            return "高位抱团";
        }
        return "无显著结构";
    }

    private static boolean isLowOrZero(Integer score) {
        return score != null && score <= 1;
    }

    /** 单组分数：0-3，该组当天没有可用样本时为 null。卡片和结构信号都读它，不各算一遍。 */
    public static Integer scoreGroup(MarketMetrics.PremiumTiers tiers, PremiumGroup group) {
        if (tiers == null) {
            return null;
        }
        BigDecimal avg = tiers.group(group).getAvgPct();
        return avg == null ? null : bandPremium(avg);
    }

    /**
     * 第 8 维 阵眼反馈：收红且创跨度新高=3、收红=2、收绿或平盘=1、
     * 收盘跌停 / 盘中触及跌停 / 断板=0。
     *
     * <p>判跌停看两个数：收盘跌停和<b>盘中触板</b>。哈药 09-03 收 -7.47% 但最低 -9.96%，
     * 那天你读"退潮一阶段"的依据就是触板本身，不是收盘那一下——只校收盘会把这一整天记成 1 分。
     *
     * <p>null = 没设阵眼 / 那天它没有行情 / 请求失败，三者都是"未评"，不能写成 0。
     */
    public static Integer calcAnchorScore(AnchorMetrics.Span span) {
        if (span == null || !span.isAvailable()) {
            return null;
        }
        if (span.isCloseLimitDown() || span.isTouchedLimitDown() || span.isBrokeToday()) {
            return 0;
        }
        BigDecimal pct = span.getPct();
        if (pct == null) {
            return null;
        }
        if (pct.signum() > 0) {
            return span.isNewSpanHigh() ? 3 : 2;
        }
        // 平盘按"没涨"处理，落 1 分：阵眼那天不动，本身既不是反馈也不是负反馈
        return 1;
    }

    /**
     * 多只在位时进分的那一个：取最差。
     *
     * <p>阵眼是哨兵，不是投票——两只里有一只跌停，这轮周期就是负反馈。取平均会把"一只崩了"
     * 稀释成"整体还行"，正好抹掉这个维唯一要报的信息。取不到分数的那几只不参与比较。
     */
    public static Integer worstAnchorScore(List<AnchorMetrics.Span> spans) {
        Integer worst = null;
        if (spans != null) {
            for (AnchorMetrics.Span span : spans) {
                Integer score = calcAnchorScore(span);
                if (score != null && (worst == null || score < worst)) {
                    worst = score;
                }
            }
        }
        return worst;
    }

    /**
     * 第 9 维 监管股今日溢价：真监管（严重异常波动 / 交易所监管）那批票当日涨跌幅的算术平均，
     * 套 03 篇同一组四档阈值。例行异常波动 ZD 只在名单上展示，不进这个均值。
     *
     * <p>家数为 null（从没拉过）或 0（拉过了、当天确实没有进分的监管股）都整维未评。
     * 「今天没有票被真监管」是个中性事实，既不是利空也不是利多，用 0 分冒充等于凭空造一次退潮。
     * 家数&gt;0 但一只都没取到涨跌同样未评，这时界面上要显示"进分 N 家，涨跌未取得"。
     */
    static Integer calcSurvivalScore(Integer count, BigDecimal avgPct) {
        return survivalScored(count, avgPct) ? bandPremium(avgPct) : null;
    }

    /**
     * 第 9 维这一趟到底进没进分。
     *
     * <p>公开出去是因为依据串也欠他一个解释：手改了溢价却没填家数（或那天家数是 0）时，
     * 这一维照样不进分，那句话必须和这里的判据同一个来源，不能各写一遍。
     */
    public static boolean survivalScored(Integer count, BigDecimal avgPct) {
        return count != null && count != 0 && avgPct != null;
    }

    /** 涨停 vs 跌停家数：远多于=3, 略多=2, 相当=1, 跌停更多=0, 跌停碾压(≥2 倍且≥20 家)=-1。 */
    static Integer calcBreadthScore(DailyRecord record) {
        Integer up = record.getLimitUpCount();
        Integer down = record.getLimitDownCount();
        if (up == null || down == null) {
            return null;
        }
        if (down == 0 && up > 20) {
            return 3;
        }
        if (up > down * 3) {
            return 3;
        }
        if (up > down) {
            return 2;
        }
        if (up.intValue() == down.intValue()) {
            return 1;
        }
        // 原文"跌停更多"是一整块 0 分。涨停 3 跌停 6 和涨停 5 跌停 40 都落在这块里，
        // 后者是单方面的屠杀，得和"只是不好看"分开——所以再要求绝对家数，避免 1 对 3 就判崩。
        return down >= up * 2 && down >= 20 ? DIM_MIN : 0;
    }

    /**
     * 第 4 维：炸板率 + 家数封板率 + 回封率，三个子项各自出分后取平均。
     *
     * <p>三个数量的是三件事，谁也不能替谁：炸板率数打开<b>次数</b>（一只票炸三次算 3 次），
     * 家数封板率数今天<b>封住了几家</b>，回封率数<b>炸开之后还接不接得住</b>。
     * 03 篇只给了炸板率那一档，另两套刻度是按 14 天实测分布两端各留一天定的——
     * 所以三条算式一律进 note，卡在 hover 上能直接核。
     *
     * <p>子项缺数按在场子项平均（不是按 0 计入）：那天没有盘面明细只代表封板率未知，
     * 不代表"封板率确认为 0"。三项全缺才整维未评。
     */
    public static BrokenDim calcBrokenDim(DailyRecord record, ScoreInputs in) {
        BigDecimal rate = record == null ? null : record.getBrokenBoardRate();
        PoolCounts counts = in == null ? null : in.getPoolCounts();
        // 人工值叠在"百分数"这一层，家数照旧只有一份：把封板率反推成家数塞回 PoolCounts
        // 就造出了两个能各自漂移的数，而这条 note 里印的算式必须跟着假。
        BigDecimal manualSealed = record == null ? null : record.getManualSealedHomeRate();
        BigDecimal manualReseal = record == null ? null : record.getManualResealRate();
        BigDecimal sealed = ManualOverride.pick(manualSealed, MarketMetrics.sealedHomeRate(counts));
        BigDecimal reseal = ManualOverride.pick(manualReseal, MarketMetrics.resealRate(counts));

        List<Integer> subs = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        if (rate != null) {
            int score = bandBrokenRate(rate);
            subs.add(score);
            parts.add("炸板率(次数) " + pct(rate) + "% → " + score + " 分");
        }
        String sealedNote = null;
        if (sealed != null) {
            int score = bandSealedHomeRate(sealed);
            subs.add(score);
            sealedNote = ManualOverride.used(manualSealed)
                    ? "家数封板率 " + pct(sealed) + "% → " + score + " 分（人工）"
                    : "家数封板率 " + counts.getZtCount() + "÷(" + counts.getZtCount() + "+"
                            + counts.getZbCount() + ")=" + pct(sealed) + "% → " + score + " 分";
            parts.add(sealedNote);
        }
        String resealNote = null;
        if (reseal != null) {
            int score = bandResealRate(reseal);
            subs.add(score);
            resealNote = ManualOverride.used(manualReseal)
                    ? "回封率 " + pct(reseal) + "% → " + score + " 分（人工）"
                    : "回封率 " + counts.getResealCount() + "÷(" + counts.getResealCount() + "+"
                            + counts.getZbCount() + ")=" + pct(reseal) + "% → " + score + " 分";
            parts.add(resealNote);
        }
        if (subs.isEmpty()) {
            return new BrokenDim(null, null, null, "炸板率与盘面家数都没有：这一维未评（不是 0 分）", null, null);
        }
        Integer score = average(subs);
        StringBuilder note = new StringBuilder(joinParts(parts, "｜"));
        if (subs.size() < 3) {
            note.append("｜缺 ").append(3 - subs.size()).append(" 项，按在场子项平均");
        }
        note.append("｜三分支平均 ").append(exactAverage(subs)).append(" → ").append(score).append(" 分");
        return new BrokenDim(score, sealed, reseal, note.toString(), sealedNote, resealNote);
    }

    /** 03 篇炸板率四档：低于 30%=3, 30-50%=2, 50-70%=1, 70% 以上=0。这一支不开负档，0 已是原文最低。 */
    static int bandBrokenRate(BigDecimal pct) {
        double v = pct.doubleValue();
        if (v < 30) {
            return 3;
        }
        if (v < 50) {
            return 2;
        }
        if (v < 70) {
            return 1;
        }
        return 0;
    }

    /**
     * 家数封板率五档：80 以上=3, 70 以上=2, 55 以上=1, 40 以上=0, 40 以下=DIM_MIN。
     * <b>03 篇没有这一套</b>：按 14 天实测 44.8~93.3 定标，两端各留一天落在界外。
     */
    static int bandSealedHomeRate(BigDecimal pct) {
        double v = pct.doubleValue();
        if (v >= 80) {
            return 3;
        }
        if (v >= 70) {
            return 2;
        }
        if (v >= 55) {
            return 1;
        }
        // 分母为 0 的情况已被 MarketMetrics.sealedHomeRate 挡成 null，走不到这里
        return v >= 40 ? 0 : DIM_MIN;
    }

    /**
     * 回封率五档：75 以上=3, 60 以上=2, 45 以上=1, 30 以上=0, 30 以下=DIM_MIN。
     * <b>03 篇没有这一套</b>：按 14 天实测 30.4~87.0 定标，同样两端各留一天。
     */
    static int bandResealRate(BigDecimal pct) {
        double v = pct.doubleValue();
        if (v >= 75) {
            return 3;
        }
        if (v >= 60) {
            return 2;
        }
        if (v >= 45) {
            return 1;
        }
        return v >= 30 ? 0 : DIM_MIN;
    }

    /** 子项取平均：HALF_UP 是"远离 0"，所以 -0.67 会落到 -1，和开放负档的方向一致。 */
    static Integer average(List<Integer> subs) {
        return BigDecimal.valueOf(sumOf(subs))
                .divide(BigDecimal.valueOf(subs.size()), 0, RoundingMode.HALF_UP).intValue();
    }

    /** note 里摆的是没取整前的那个数，否则"0 分"看着像从别处来的。 */
    static String exactAverage(List<Integer> subs) {
        return BigDecimal.valueOf(sumOf(subs))
                .divide(BigDecimal.valueOf(subs.size()), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static int sumOf(List<Integer> values) {
        int sum = 0;
        for (Integer value : values) {
            sum += value;
        }
        return sum;
    }

    /**
     * 第 4 维三条算式里的百分数一律写成一位小数。不过这一层，同一个数印成 "84.7" 还是 "84.70"
     * 取决于它是从表单进来的（Jackson 照他敲的位数）还是从库里读回来的（列是 DECIMAL(5,2)）——
     * 依据串里两种长相，他一定会当成取错了数。
     */
    private static String pct(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String joinParts(List<String> parts, String sep) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (text.length() > 0) {
                text.append(sep);
            }
            text.append(part);
        }
        return text.toString();
    }

    /**
     * 第 4 维的产物：合成分 + 两个家数口径百分数（卡片直接显示）+ 一条中文算式。
     *
     * <p>两条家数子算式单独留一份（与 {@code note} 里那两句逐字相同）：复盘页的子项块要一行一句
     * 摊给他核，而档位阈值只该存在于这一处——前端照着 {@code note} 再切一遍字符串，
     * 就是第二套刻度，改档的时候必然漏改一边。
     */
    public static final class BrokenDim {
        private final Integer score;
        private final BigDecimal sealedHomeRate;
        private final BigDecimal resealRate;
        private final String note;
        private final String sealedNote;
        private final String resealNote;

        BrokenDim(Integer score, BigDecimal sealedHomeRate, BigDecimal resealRate, String note,
                  String sealedNote, String resealNote) {
            this.score = score;
            this.sealedHomeRate = sealedHomeRate;
            this.resealRate = resealRate;
            this.note = note;
            this.sealedNote = sealedNote;
            this.resealNote = resealNote;
        }

        public Integer getScore() {
            return score;
        }

        public BigDecimal getSealedHomeRate() {
            return sealedHomeRate;
        }

        public BigDecimal getResealRate() {
            return resealRate;
        }

        public String getNote() {
            return note;
        }

        public String getSealedNote() {
            return sealedNote;
        }

        public String getResealNote() {
            return resealNote;
        }
    }

    /**
     * 大面数：极少(≤1)=3, 少量(≤5)=2, 偏多(≤12)=1, 成群(>12)=0, 崩盘(>25)=-1。
     *
     * <p>最后一档<b>原文没有</b>：03 篇的"成群(>12)=0"是一整块，把"差"和"崩了"拆开是这边加的，
     * 25 也不是从原文推出来的——看到不对就直接否。
     *
     * <p>更要紧的是这条维的低端<b>眼下没有样本可校</b>：已回补的 14 天大面是 0~4 家，而炸板池
     * 全天也只有 6~48 只，所以 ≤12=1、>12=0、>25=-1 三档一次都没落到过，实际只在 2/3 之间跳。
     * 12 和 25 是按"一天几百家涨停"的量级猜的，对不上这份按回撤+绿盘筛出来的口径，等样本攒够再回来挪。
     */
    static Integer calcLossScore(DailyRecord record) {
        Integer c = record.getBigLossCount();
        if (c == null) {
            return null;
        }
        if (c <= 1) {
            return 3;
        }
        if (c <= 5) {
            return 2;
        }
        if (c <= 12) {
            return 1;
        }
        return c <= 25 ? 0 : DIM_MIN;
    }

    /**
     * 量能/换手：温和放大=3, 缩量或天量=1, 背离=0。
     *
     * 03 篇这一维只有三档，没有"基本持平=2"——持平按使用者的决定归到 3。
     * "背离"原文要的是量能与情绪反向，系统里没存指数涨跌，改用同日"昨日涨停溢价"的符号当情绪方向：
     * 放量但昨日涨停股今天在亏钱 = 量增价滞；缩量却情绪亢奋 = 无量空涨。
     * 溢价没填时不做背离判断，只在量级上给 3 或 1。
     */
    static Integer calcVolumeScore(DailyRecord record, List<DailyRecord> recent) {
        BigDecimal vol = record.getTotalVolume();
        if (vol == null || vol.signum() == 0) {
            return null;
        }
        if (recent == null || recent.size() < 2) {
            return null;
        }

        double sum = 0;
        int count = 0;
        // recent 已按 tradeDate 倒序（最新在前），正向遍历才能取到最近 3 日
        for (int i = 0; i < recent.size() && count < 3; i++) {
            BigDecimal v = recent.get(i).getTotalVolume();
            if (v != null && v.signum() > 0) {
                sum += v.doubleValue();
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        double ratio = vol.doubleValue() / (sum / count);

        BigDecimal premium = moodReference(record);
        boolean moodUp = premium != null && premium.doubleValue() > 4;
        boolean moodDown = premium != null && premium.signum() < 0;

        if (ratio < 0.85) {
            return moodUp ? 0 : 1;
        }
        if (ratio <= 1.3) {
            return 3;
        }
        if (ratio <= 1.5) {
            return moodDown ? 0 : 3;
        }
        return moodDown ? 0 : 1;
    }

    /**
     * 量能维要的只是一个"情绪方向"的旁证，所以优先用含首板的整池溢价——
     * 它是这一天口径最宽的那个均值，换溢价打分口径不该顺手把已有的量能读数改掉。
     * 整池没填（历史日常见）才退到分档合成值。
     */
    private static BigDecimal moodReference(DailyRecord record) {
        BigDecimal pooled = record.getYesterdayLimitPremium();
        return pooled != null ? pooled : record.getPremiumWeighted();
    }

    /**
     * 主线/龙头明确度：有清晰主线+龙头=3, 有热点无主线=1, 无主线=0。
     * 这是九维里唯一由人填的档位，没填就是未评——不许拿 0 分冒充"判断过、判断为无主线"。
     * 原文没有 2 分档，历史数据里的 2 一律夹到 1。
     */
    static Integer calcThemeScore(DailyRecord record) {
        Integer v = record.getScoreTheme();
        if (v == null) {
            return null;
        }
        if (v >= 3) {
            return 3;
        }
        return v >= 1 ? 1 : 0;
    }

    public static String determineStage(double temperature, Double prevTemperature) {
        if (prevTemperature != null && temperature - prevTemperature <= -DROP_THRESHOLD) {
            return temperature < TIDE_LINE ? "退潮" : "分歧";
        }
        if (temperature <= 15) {
            return "冰点";
        }
        if (temperature >= 80) {
            return "高潮";
        }
        if (temperature >= FERMENT_LINE) {
            return "发酵";
        }
        if (temperature >= 35) {
            return "启动";
        }
        return "修复";
    }

    public static String determineDirection(double temperature, Double prevTemperature) {
        if (prevTemperature == null) {
            return "横盘";
        }
        double delta = temperature - prevTemperature;
        if (delta > 5) {
            return "上升";
        }
        if (delta < -5) {
            return "下降";
        }
        return "横盘";
    }
}
