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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;


/**
 * 九维情绪打分引擎，对齐新 100 分制规则。
 *
 * <p>每维 -3 ~ +3，各带权重（成交额x2、涨跌停x2、主线x2、阵眼x1.5、炸板x1.5，其余x1），
 * 加权总和理论范围 +/-37.5，线性映射到 0~100。
 * 阶段：冰点<=15, 修复<35, 启动<55, 发酵<80, 高潮>=80。
 */

public class TemperatureCalculator {

    /** 每维最高分 +3 */
    public static final int DIM_MAX = 3;
    /** 每维最低分 -3 */
    public static final int DIM_MIN = -3;
    /** 至少要有这么多维度才出阶段 */
    public static final int MIN_DIMS_FOR_STAGE = 5;
    /** 判断转退潮 */
    private static final double DROP_THRESHOLD = 12;
    /** 退出潮线的分界 */
    private static final double TIDE_LINE = 40;
    /** 发酵线 */
    public static final double FERMENT_LINE = 55;

    // 各维度权重
    public static final double W_VOLUME  = 2.0;
    public static final double W_BREADTH = 2.0;
    public static final double W_HEIGHT  = 1.0;
    public static final double W_PREMIUM = 1.0;
    public static final double W_BROKEN  = 1.5;
    public static final double W_THEME   = 2.0;
    public static final double W_ANCHOR  = 1.5;
    public static final double W_LOSS    = 1.0;
    public static final double W_SURVIVAL = 1.0;
    /** 权重总和 x DIM_MAX = 12.5 x 3 = 37.5 */
    public static final double MAX_POSSIBLE = (W_VOLUME + W_BREADTH + W_HEIGHT + W_PREMIUM
            + W_BROKEN + W_THEME + W_ANCHOR + W_LOSS + W_SURVIVAL) * DIM_MAX;

    // 分档溢价内部权重（低/中/高）
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
        Integer volume = calcVolumeScore(record);
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

        Map<String, Integer> byKey = dimensionScores(height, premium, breadth, broken, loss,
                volume, theme, anchor, survival);
        ScoringModel model = in.getScoringModel() != null ? in.getScoringModel() : builtinModel();
        double weightedSum = 0;
        int scored = 0;
        for (DimWeight d : model.getDims()) {
            Integer v = byKey.get(d.getDimKey());
            if (v != null) {
                scored++;
                weightedSum += v * d.getWeight();
            }
        }

        double maxPossible = maxScoreOf(model);
        double temperature = (weightedSum + maxPossible) / (2 * maxPossible) * 100;

        record.setScoredDims(scored);
        record.setTotalScore(scored == 0 ? null : (int) Math.round(weightedSum));
        record.setTemperature(scored == 0 ? null
                : BigDecimal.valueOf(temperature).setScale(1, RoundingMode.HALF_UP));
    }


    /** 内置默认模型：维集合与权重逐字取自上面的 W_* 常量，max_score=null（按权重和×每维满分现推）。 */
    public static ScoringModel builtinModel() {
        List<DimWeight> dims = Arrays.asList(
                new DimWeight("height",  "连板高度",   W_HEIGHT,   1),
                new DimWeight("premium", "分档溢价",   W_PREMIUM,  2),
                new DimWeight("breadth", "涨停/跌停",  W_BREADTH,  3),
                new DimWeight("broken",  "炸板率",     W_BROKEN,   4),
                new DimWeight("loss",    "大面数",     W_LOSS,     5),
                new DimWeight("volume",  "成交额",     W_VOLUME,   6),
                new DimWeight("theme",   "主线明确度", W_THEME,    7),
                new DimWeight("anchor",  "阵眼当日",   W_ANCHOR,   8),
                new DimWeight("surv",    "异动监管",   W_SURVIVAL, 9));
        ScoringModel model = new ScoringModel();
        model.setModelKey("ultra_short");
        model.setName("超短情绪模型");
        model.setDims(Collections.unmodifiableList(dims));
        model.setMaxScore(null);
        return model;
    }

    /** 温度映射分母：模型写死且 >0 用它，否则按权重和 × 每维满分现推；兜到 MAX_POSSIBLE 防 0/NaN。 */
    static double maxScoreOf(ScoringModel model) {
        if (model == null) {
            return MAX_POSSIBLE;
        }
        if (model.getMaxScore() != null && model.getMaxScore() > 0) {
            return model.getMaxScore();
        }
        double sum = 0;
        if (model.getDims() != null) {
            for (DimWeight d : model.getDims()) {
                sum += d.getWeight();
            }
        }
        double derived = sum * DIM_MAX;
        return derived > 0 ? derived : MAX_POSSIBLE;
    }

    /** 九维出分按 dim_key 收进一个 Map；聚合时按键取权重（顺序无关，防 dim_no 写错导致两维权重串行）。 */
    private static Map<String, Integer> dimensionScores(Integer height, Integer premium,
            Integer breadth, Integer broken, Integer loss, Integer volume, Integer theme,
            Integer anchor, Integer survival) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("height", height);
        map.put("premium", premium);
        map.put("breadth", breadth);
        map.put("broken", broken);
        map.put("loss", loss);
        map.put("volume", volume);
        map.put("theme", theme);
        map.put("anchor", anchor);
        map.put("surv", survival);
        return map;
    }

    /** 连板高度：绝对档位 >=7=3, >=5=2, >=3=1, 2=0, 1=-1, 0=-3 */
    static Integer calcHeightScore(DailyRecord record, List<DailyRecord> recent) {
        Integer h = record.getMaxConsecutiveLimit();
        if (h == null) {
            return null;
        }
        if (h >= 7) return 3;
        if (h >= 5) return 1;
        if (h >= 3) return -1;
        if (h == 2) return -3;
        if (h == 1) return -3;
        return -3;
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


    /** 新七档溢价：>5%=3, >3%=2, >1%=1, >=0%=0, >=-3%=-1, >=-5%=-2, <-5%=-3 */
    static int bandPremium(BigDecimal pct) {
        double v = pct.doubleValue();
        if (v > 5) return 3;
        if (v > 3) return 2;
        if (v > 1) return 0;
        if (v >= 0) return -1;
        if (v >= -3) return -2;
        if (v >= -5) return -3;
        return -3;
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
     * 阵眼当日：强涨停(>=9.5%)=3, 红盘(>0)=2, 平盘=0, 断板=-1, 按核(<=-5%)=-2, 跌停=-3
     * null = 没有阵眼 / 数据不可用。
     */
    public static Integer calcAnchorScore(AnchorMetrics.Span span) {
        if (span == null || !span.isAvailable()) {
            return null;
        }
        BigDecimal pct = span.getPct();
        if (span.isCloseLimitDown() || span.isTouchedLimitDown()) {
            return -3;
        }
        if (pct != null && pct.doubleValue() <= -5) {
            return -2;
        }
        if (span.isBrokeToday()) {
            return -1;
        }
        if (pct == null) {
            return null;
        }
        double v = pct.doubleValue();
        if (v >= 9.5) return 3;
        if (v > 0) return 2;
        return 0;
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
     * 第 9 维涨停板接力：按 avgPct 状态打分。
     * >=9.5%=3, >0%=2, >=-2%=-1, >=-5%=-2, <-5%=-3
     */
    static Integer calcSurvivalScore(Integer count, BigDecimal avgPct) {
        if (!survivalScored(count, avgPct)) {
            return null;
        }
        double v = avgPct.doubleValue();
        if (v >= 9.5) return 3;
        if (v > 0) return 2;
        if (v >= -2) return -1;
        if (v >= -5) return -2;
        return -3;
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

    /** 涨跌停对比（绝对数） */
    static Integer calcBreadthScore(DailyRecord record) {
        Integer up = record.getLimitUpCount();
        Integer down = record.getLimitDownCount();
        if (up == null || down == null) {
            return null;
        }
        if (up < 20 && down >= 40) return -3;
        if (up < 30 && down >= 20) return -2;
        if (up > 80 && down == 0) return 3;
        if (up >= 60 && down < 3) return 2;
        if (up >= 50 && down < 5) return 1;
        if ((up >= 40 && up < 50) || (down >= 5 && down <= 10)) return 0;
        return -1;
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

    /** 新炸板率档位：<20%=3, <30%=2, <40%=1, <50%=-1, <=70%=-2, >70%=-3 */
    static int bandBrokenRate(BigDecimal pct) {
        double v = pct.doubleValue();
        if (v < 20) return 3;
        if (v < 30) return 2;
        if (v < 40) return 1;
        if (v < 50) return -1;
        if (v <= 70) return -2;
        return -3;
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

    /** 大面数：0=3, 1-2=2, 3-4=1, 5-9=-1, 10-20=-2, >20=-3 */
    static Integer calcLossScore(DailyRecord record) {
        Integer c = record.getBigLossCount();
        if (c == null) {
            return null;
        }
        if (c == 0) return 3;
        if (c <= 3) return 2;
        if (c <= 7) return 1;
        if (c <= 12) return -1;
        if (c <= 20) return -2;
        return -3;
    }


    /** 成交额（绝对值，单位亿） */
    static Integer calcVolumeScore(DailyRecord record) {
        BigDecimal vol = record.getTotalVolume();
        if (vol == null || vol.signum() == 0) {
            return null;
        }
        double v = vol.doubleValue();
        if (v > 30000) return 3;
        if (v >= 25000) return 2;
        if (v >= 19000) return 1;
        if (v >= 18000) return 0;
        if (v >= 17000) return -1;
        if (v >= 15000) return -2;
        return -3;
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

    /** 主线明确度：直接取 scoreTheme（-3~+3 六档），null 为未评 */
    static Integer calcThemeScore(DailyRecord record) {
        Integer v = record.getScoreTheme();
        if (v == null) {
            return null;
        }
        if (v >= 3) return 3;
        if (v >= 2) return 2;
        if (v >= 1) return 1;
        if (v <= -3) return -3;
        if (v <= -2) return -2;
        if (v <= -1) return -1;
        return 0;
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
