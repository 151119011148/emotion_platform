package com.emotion.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.emotion.entity.DailyRecord;
import com.emotion.market.MarketMetrics;
import com.emotion.market.PoolCounts;
import com.emotion.market.PoolRow;
import com.emotion.market.PremiumGroup;

/**
 * 温度计打分与阶段判定的口径。这些断言直接决定仪表盘给出什么操作建议，
 * 所以覆盖的重点不是"算得对"，而是"每一档都能被算到、缺数据不会被算成一个读数"。
 *
 * <p>加权口径分母固定 39（= 九个权重之和 13.0 × 3），未评的维只是少加它那一份，所以在温度上<b>与 0 分不可区分</b>——
 * 未评靠 {@code scored_dims} 和卡面上的"—"表达，别把这个特性当成 bug 改回去。
 */
class TemperatureCalculatorTest {

    /**
     * 七维齐全的一条记录。溢价维不在这里——它读的是当日档位表（{@link #inputsOf}），
     * yesterdayLimitPremium 现在只是含首板的展示值。
     */
    private static DailyRecord full() {
        DailyRecord r = new DailyRecord();
        r.setMaxConsecutiveLimit(5);
        r.setLimitUpCount(60);
        r.setLimitDownCount(2);
        r.setYesterdayLimitPremium(new BigDecimal("2.20"));
        r.setBrokenBoardRate(new BigDecimal("42.2"));
        r.setBigLossCount(1);
        r.setTotalVolume(new BigDecimal("18000"));
        r.setScoreTheme(3);
        return r;
    }

    /**
     * 只有一组在场的一天：最高 3 板 → M=2，2/3 板都算<b>中位</b>（低档在结构上是空的），
     * 均值 +2.20% 判 0 分。单组时权重不起作用。
     */
    private static final ScoreInputs MID_ONLY = inputsOf("2:2.20", "3:2.20");

    /** 造一日档位溢价：每对 "连板数:涨幅%" 一只票。 */
    private static MarketMetrics.PremiumTiers tiersOf(String... boardAndPct) {
        List<PoolRow> rows = new ArrayList<>();
        Map<String, BigDecimal> pct = new HashMap<>();
        for (int i = 0; i < boardAndPct.length; i++) {
            String[] parts = boardAndPct[i].split(":");
            String code = String.format("60%04d", i);
            PoolRow row = new PoolRow();
            row.setCode(code);
            row.setMarket(1);
            row.setLbc(Integer.parseInt(parts[0]));
            rows.add(row);
            pct.put(code, new BigDecimal(parts[1]));
        }
        return MarketMetrics.premiumTiers(rows, pct);
    }

    /** 同 tiersOf，但 "板:" 后面留空表示这一档有票却一只价都没取到（matched=0，均值为 null）。 */
    private static MarketMetrics.PremiumTiers tiersWithBlindTop(String... boardAndPct) {
        List<PoolRow> rows = new ArrayList<>();
        Map<String, BigDecimal> pct = new HashMap<>();
        for (int i = 0; i < boardAndPct.length; i++) {
            String[] parts = boardAndPct[i].split(":");
            String code = String.format("60%04d", i);
            PoolRow row = new PoolRow();
            row.setCode(code);
            row.setMarket(1);
            row.setLbc(Integer.parseInt(parts[0]));
            rows.add(row);
            if (parts.length > 1 && !parts[1].isEmpty()) {
                pct.put(code, new BigDecimal(parts[1]));
            }
        }
        return MarketMetrics.premiumTiers(rows, pct);
    }

    private static ScoreInputs inputsOf(String... boardAndPct) {
        ScoreInputs inputs = ScoreInputs.empty();
        inputs.setPremiumTiers(tiersOf(boardAndPct));
        return inputs;
    }

    /** 造一份三天历史喂给签名：这一版高度/量能都不读它，喂与不喂是同一个数。 */
    private static List<DailyRecord> history(String... volumes) {
        List<DailyRecord> list = new ArrayList<>();
        for (String v : volumes) {
            DailyRecord r = new DailyRecord();
            r.setTotalVolume(new BigDecimal(v));
            r.setMaxConsecutiveLimit(4);
            list.add(r);
        }
        return list;
    }

    // ---------- 未评的维度整维不进分子，也不许被兜底造出一个分 ----------

    /**
     * 分母是九个权重现算出来的 13.0 × 3 = <b>39.0</b>，不是 {@code MAX_POSSIBLE} 那句注释写的
     * 12.5 × 3 = 37.5。这条断言存在的意义就是把两个数钉在一起：改任何一个权重都会在这里变红，
     * 而不是悄悄让温度尺子和注释各说一套。
     */
    @Test
    void maxPossibleFollowsTheWeightsNotTheirComment() {
        double sum = TemperatureCalculator.W_VOLUME + TemperatureCalculator.W_BREADTH
                + TemperatureCalculator.W_HEIGHT + TemperatureCalculator.W_PREMIUM
                + TemperatureCalculator.W_BROKEN + TemperatureCalculator.W_THEME
                + TemperatureCalculator.W_ANCHOR + TemperatureCalculator.W_LOSS
                + TemperatureCalculator.W_SURVIVAL;
        assertEquals(13.0, sum, 0.0001);
        assertEquals(sum * TemperatureCalculator.DIM_MAX, TemperatureCalculator.MAX_POSSIBLE, 0.0001);
    }

    @Test
    void absentAnchorAndSurveillanceStayOutOfTheNumerator() {
        DailyRecord r = full();
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), MID_ONLY);
        // 七维：高度1(5板) + 溢价0(中位+2.2%) + 涨跌停比2 + 炸板-1 + 大面2 + 量能0 + 明确度3
        // 加权 1×1 + 0×1 + 2×2 + (−1)×1.5 + 2×1 + 0×2 + 3×2 = 11.5
        // 分母 = 九个权重之和 13.0 × 3 = 39 → (11.5+39)/78 = 64.7°
        assertEquals(7, dim(r));
        assertEquals(12, total(r));
        assertEquals(0, new BigDecimal("64.7").compareTo(r.getTemperature()));
        // 第 8/9 维没取到：整维不进分子。分母不再跟着已评维数缩放，缺维只是"不加分"，
        // 不像旧口径那样按 3 分把总分顶上去。
        assertNull(r.getAnchorScore());
        assertNull(r.getSurvCount());
    }

    // ---------- 第 8 维 阵眼、第 9 维 监管股今日溢价 ----------

    /** 九维齐全：分母恒为 39，两维进分只往分子上加减，不再像旧口径那样把分母撑大。 */
    @Test
    void nineCompleteDimsScoreIntoAFixedDenominator() {
        DailyRecord r = full();
        // 不能改 MID_ONLY：它是共享常量，改一次就污染后面每一个用例
        ScoreInputs in = inputsOf("2:2.20", "3:2.20");
        in.setAnchorScore(0);
        in.setAnchorNote("0 分｜哈药股份 盘中触板 -7.47%（最低 -9.96%） · 跨度第 40 日 · 最高 5 板");
        in.setSurvCount(2);
        in.setSurvPremium(new BigDecimal("-3.00"));
        in.setSurvNote("监管股今日溢价 = -3.00% = 2 只涨幅均值（进分 2 家：严重异常波动/交易所监管）");
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), in);

        assertEquals(9, dim(r));
        // 七维那 11.5 不动，阵眼 0×1.5 不加分、第 9 维 -3% 判 -2 → 9.5
        // 缺维时也是同一个 39 做分母：所以"哨兵进分"只按它自己的分数说话，不再稀释别人
        assertEquals(10, total(r));
        assertEquals(0, new BigDecimal("62.2").compareTo(r.getTemperature()));
        assertEquals(0, r.getAnchorScore().intValue());
        assertEquals(-2, TemperatureCalculator.calcSurvivalScore(2, new BigDecimal("-3.00")).intValue());
        // 依据串必须跟着落库：界面上要能追问这个 0 分是谁给的
        assertTrue(r.getAnchorNote().contains("盘中触板"));
        assertTrue(r.getSurvNote().contains("进分 2 家"));
    }

    /**
     * 进分家数 0 = 拉过了、当天没有 SEVERE/EXCH 在列（可能有一堆例行异常波动，那些只展示）：
     * 中性事实，整维未评，但 0 本身要落库（与 null 分得开）。
     */
    @Test
    void noStockUnderSurveillanceLeavesTheDimOutButKeepsTheZero() {
        DailyRecord r = full();
        ScoreInputs in = inputsOf("2:2.20", "3:2.20");
        in.setSurvCount(0);
        in.setSurvPremium(null);
        in.setSurvNote("当日无严重异常波动/交易所监管在列（第 9 维不计入分母，不是 0 分）；"
                + "另有 8 只例行异常波动，只展示不进分");
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), in);

        assertEquals(0, r.getSurvCount().intValue());
        assertEquals(7, dim(r));
        // 家数 0 = 整维不进分子，读数与"没接上监管数据"那天完全一样：64.7°
        assertEquals(12, total(r));
        assertEquals(0, new BigDecimal("64.7").compareTo(r.getTemperature()));
    }

    @Test
    void survivalBandsReuseThePremiumThresholds() {
        assertNull(TemperatureCalculator.calcSurvivalScore(null, new BigDecimal("5.00")));
        assertNull(TemperatureCalculator.calcSurvivalScore(0, new BigDecimal("5.00")));
        assertNull(TemperatureCalculator.calcSurvivalScore(3, null));
        assertEquals(3, TemperatureCalculator.calcSurvivalScore(3, new BigDecimal("9.50")).intValue());
        assertEquals(2, TemperatureCalculator.calcSurvivalScore(3, new BigDecimal("4.50")).intValue());
        assertEquals(2, TemperatureCalculator.calcSurvivalScore(3, new BigDecimal("0.10")).intValue());
        assertEquals(-1, TemperatureCalculator.calcSurvivalScore(3, new BigDecimal("-1.90")).intValue());
        assertEquals(-2, TemperatureCalculator.calcSurvivalScore(3, new BigDecimal("-2.10")).intValue());
        assertEquals(-2, TemperatureCalculator.calcSurvivalScore(3, new BigDecimal("-4.90")).intValue());
        assertEquals(-3, TemperatureCalculator.calcSurvivalScore(3, new BigDecimal("-5.10")).intValue());
    }

    @Test
    void unfilledThemeIsExcludedInsteadOfScoringZero() {
        DailyRecord r = full();
        r.setScoreTheme(null);
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), MID_ONLY);
        assertNull(r.getScoreTheme());
        assertEquals(6, dim(r));
        // 分母固定 39，未评只是少加它那一份（11.5 − 3×2 = 5.5 → 57.1°）：
        // 旧口径"按已评维数摊分"会让一个还没判断凭空吃掉 14.3°，现在这个后果没有了。
        // 代价是未评与 0 分在温度上不可区分，未评只能靠 scored_dims 和卡面上的"—"表达。
        assertEquals(6, total(r));
        assertEquals(0, new BigDecimal("57.1").compareTo(r.getTemperature()));
    }

    @Test
    void everyDimMissingGivesNoReadingAtAll() {
        DailyRecord r = new DailyRecord();
        TemperatureCalculator.calculate(r, new ArrayList<DailyRecord>());
        assertEquals(0, dim(r));
        assertNull(r.getTotalScore());
        assertNull(r.getTemperature());
        assertNull(r.getScoreHeight());
    }

    @Test
    void zeroStoredValueIsNotConfusedWithMissing() {
        // 大面 0 家是真实读数（给 3 分）；炸板率没填则是未评，不能按 0% 给满分
        DailyRecord r = full();
        r.setBigLossCount(0);
        r.setBrokenBoardRate(null);
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), MID_ONLY);
        assertEquals(3, TemperatureCalculator.calcLossScore(r).intValue());
        assertNull(r.getScoreBroken());
        assertEquals(6, dim(r));
    }

    // ---------- 高度/量能都是绝对刻度：没有历史照样出分 ----------

    @Test
    void absoluteDimsStillScoreWithoutHistory() {
        DailyRecord r = full();
        TemperatureCalculator.calculate(r, new ArrayList<>(Arrays.asList(new DailyRecord())), MID_ONLY);
        // full()：5 板 → 1 分、18000 亿 → 0 分，七维全部有读数。
        // 这一维不再拿近期均量/近期最高板做基准，所以"回补第一天"和"跑了一年"给的是同一个数。
        assertEquals(7, dim(r));
    }

    /**
     * 连板高度是绝对刻度：7 板以上才给 3，塌到 2 板以下一律判到底。
     * 近期历史已经不参与这一维，所以同一份"塌回首板"在两种池子里必须是同一个数。
     */
    @Test
    void heightTiersAreAbsoluteAndHistoryFree() {
        assertEquals(3, height(8));
        assertEquals(3, height(7));
        assertEquals(1, height(6));
        assertEquals(1, height(5));
        assertEquals(-1, height(4));
        assertEquals(-1, height(3));
        assertEquals(-3, height(2));
        assertEquals(-3, height(1));
        assertEquals(-3, height(0));

        DailyRecord r = full();
        r.setMaxConsecutiveLimit(1);
        assertEquals(-3, TemperatureCalculator.calcHeightScore(r, history("18000", "18000", "18000")).intValue());
        assertEquals(-3, TemperatureCalculator.calcHeightScore(r, lowHistory(3)).intValue());
        assertNull(TemperatureCalculator.calcHeightScore(new DailyRecord(), history("18000")));
    }

    private static int height(int board) {
        DailyRecord r = new DailyRecord();
        r.setMaxConsecutiveLimit(board);
        return TemperatureCalculator.calcHeightScore(r, new ArrayList<DailyRecord>()).intValue();
    }

    // ---------- 负档：这一版九维全部开到 -3 ----------

    /**
     * 溢价七档：>5%=3, >3%=2, >1%=0, >=0%=-1, >=-3%=-2, >=-5%=-3, 其余=-3。
     * 这一版把正档整体下移一格，压线的 5.00 / 3.00 / 1.00 各自落在哪一档必须钉住——
     * ">" 与 ">=" 混用，差一个符号就是差两分。
     */
    @Test
    void premiumSevenTiers() {
        assertEquals(3, TemperatureCalculator.bandPremium(new BigDecimal("5.01")));
        assertEquals(2, TemperatureCalculator.bandPremium(new BigDecimal("5.00")));
        assertEquals(2, TemperatureCalculator.bandPremium(new BigDecimal("3.50")));
        assertEquals(0, TemperatureCalculator.bandPremium(new BigDecimal("3.00")));
        assertEquals(0, TemperatureCalculator.bandPremium(new BigDecimal("1.50")));
        assertEquals(-1, TemperatureCalculator.bandPremium(new BigDecimal("1.00")));
        assertEquals(-1, TemperatureCalculator.bandPremium(new BigDecimal("0.00")));
        assertEquals(-2, TemperatureCalculator.bandPremium(new BigDecimal("-2.00")));
        assertEquals(-2, TemperatureCalculator.bandPremium(new BigDecimal("-3.00")));
        assertEquals(-3, TemperatureCalculator.bandPremium(new BigDecimal("-3.01")));
        assertEquals(-3, TemperatureCalculator.bandPremium(new BigDecimal("-5.00")));
        assertEquals(-3, TemperatureCalculator.bandPremium(new BigDecimal("-5.01")));
        assertEquals(-3, TemperatureCalculator.bandPremium(new BigDecimal("-10.73")));
    }

    @Test
    void breadthNegativeOnlyForOneSidedSlaughter() {
        assertEquals(0, TemperatureCalculator.calcBreadthScore(breadth(3, 6)).intValue());
        assertEquals(-2, TemperatureCalculator.calcBreadthScore(breadth(20, 39)).intValue());
        assertEquals(-3, TemperatureCalculator.calcBreadthScore(breadth(5, 40)).intValue());
        assertEquals(-2, TemperatureCalculator.calcBreadthScore(breadth(15, 25)).intValue());
        assertEquals(3, TemperatureCalculator.calcBreadthScore(breadth(81, 0)).intValue());
        assertEquals(2, TemperatureCalculator.calcBreadthScore(breadth(65, 2)).intValue());
        assertEquals(1, TemperatureCalculator.calcBreadthScore(breadth(50, 4)).intValue());
        // 45 家涨停配 4 家跌停：没够到 ">=50 且 <5"，落进 "40~50 家" 那个持平格
        assertEquals(0, TemperatureCalculator.calcBreadthScore(breadth(45, 4)).intValue());
    }

    /** 量能/主线两维的负档是新加的刻度，但 0 仍是"持平"这一格，没有被挪位。 */
    @Test
    void zeroIsStillTheFlatReadingForVolumeAndTheme() {
        assertEquals(0, volume("18000"));
        assertEquals(0, theme(0));
    }

    // ---------- 溢价：分档合成，高位权重更重，缺档摊回 ----------

    @Test
    void highTierOutweighsLowTierOnTheSameDay() {
        // 低位 +5% 判 2、高位 -5% 判 -3：(1×2 + 2.5×−3)/3.5 = −1.57 → -2
        // 同一组数反过来摆：(1×−3 + 2.5×2)/3.5 = 0.57 → 1 —— 只有权重能定出这个方向
        assertEquals(-2, TemperatureCalculator.calcPremiumScore(tiersOf("2:5.00", "7:-5.00")).intValue());
        assertEquals(1, TemperatureCalculator.calcPremiumScore(tiersOf("2:-5.00", "7:5.00")).intValue());
    }

    @Test
    void absentTierRenormalizesInsteadOfDiluting() {
        // 低位 +2.2% 判 0、高位 +5% 判 2，中位那天根本没有票：(1×0 + 2.5×2)/3.5 = 1.43 → 1
        MarketMetrics.PremiumTiers tiers = tiersOf("2:2.20", "5:5.00");
        assertNull(TemperatureCalculator.scoreGroup(tiers, PremiumGroup.MID));
        assertEquals(1, TemperatureCalculator.calcPremiumScore(tiers).intValue());
        // 上面这组按 0 计入缺席中位也是 (0+0+5)/5 = 1，看不出差别。换低位也在赚钱的一天：
        // 摊回 = (1×2 + 2.5×2)/3.5 = 2；按 0 计入分母 = (2+0+5)/5 = 0.9 → 1，凭空掉一分。
        assertEquals(2, TemperatureCalculator.calcPremiumScore(tiersOf("2:5.00", "5:5.00")).intValue());
    }

    @Test
    void noTierAtAllLeavesTheDimUnscored() {
        assertNull(TemperatureCalculator.calcPremiumScore(tiersOf()));
        assertNull(TemperatureCalculator.calcPremiumScore(null));

        DailyRecord r = full();
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), inputsOf());
        assertNull(r.getScorePremium());
        assertEquals(6, dim(r));
        // 这一天恰好：溢价自动分本就是 0，未评少加的是 0×1 → 与七维那条逐字相同的 64.7°。
        // 换句话说在温度上"未评"和"0 分"是同一个数，只能在 scored_dims 和卡面上分开。
        assertEquals(12, total(r));
        assertEquals(0, new BigDecimal("64.7").compareTo(r.getTemperature()));
    }

    @Test
    void pooledScalarIsDisplayOnly() {
        // 含首板整池 +9.99% 看着像 3 分，但分档后在亏钱的两档合成 -5.50% → 跌破 -5%，进分的是 -3
        DailyRecord r = full();
        r.setYesterdayLimitPremium(new BigDecimal("9.99"));
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), inputsOf("2:-5.00", "3:-6.00"));
        assertEquals(-3, r.getScorePremium().intValue());
        assertEquals(0, new BigDecimal("-5.50").compareTo(r.getPremiumWeighted()));
    }

    @Test
    void compositePercentFollowsGroupWeights() {
        assertEquals(0, new BigDecimal("-2.14")
                .compareTo(TemperatureCalculator.compositePremiumPct(tiersOf("2:5.00", "7:-5.00"))));
    }

    @Test
    void structureLabelsCoverTheThreeReadingsThatMatter() {
        assertEquals("全面负反馈", TemperatureCalculator.premiumStructure(tiersOf("2:-5.00", "4:-3.00", "6:-4.00")));
        assertEquals("中位负反馈吹哨", TemperatureCalculator.premiumStructure(tiersOf("6:5.00", "4:-1.00")));
        assertEquals("高低切", TemperatureCalculator.premiumStructure(tiersOf("2:5.00", "6:-3.00")));
        assertEquals("高位抱团", TemperatureCalculator.premiumStructure(tiersOf("2:1.00", "6:5.00")));
        assertEquals("无显著结构", TemperatureCalculator.premiumStructure(tiersOf("2:5.00", "4:5.00")));
        // 只有一组在场时说不出"钱在怎么切"，不能硬套一个标签
        assertEquals("仅一组", TemperatureCalculator.premiumStructure(tiersOf("2:2.20")));
        assertEquals("无样本", TemperatureCalculator.premiumStructure(tiersOf()));
    }

    /**
     * 动态分档把"高位断层"逼到只剩一条来路：当天最高板自己就落在高位组（H≥4 时 M+2≤H），
     * 所以"顶端一只都不在场"这个市场形态再也表达不出来。现在只有"顶端那档有票、却一只价都没取到"
     * 会走到这个标签——那是数据缺口，不是断层。
     *
     * <p>判据要不要跟着改，等 14 天结构标签对照表出来再定。这里把现状钉住，别让它悄悄变味。
     */
    @Test
    void absentHighTierWithWeakLowIsAFaultLine() {
        assertEquals("高位断层", TemperatureCalculator.premiumStructure(
                tiersWithBlindTop("2:-1.94", "4:1.19", "7:")));
        // 同一块盘面（顶端价取到了）：中位 -1.94% 判 -2、高位 +1.19% 只判 0，
        // 没够到"高位还在赚钱"那个 >=2 的门槛，所以只是无显著结构，不吹哨
        assertEquals("无显著结构", TemperatureCalculator.premiumStructure(tiersOf("2:-1.94", "4:1.19")));
        // 反过来：高位 +9.99% 判 3、中位 +3.00% 恰好没越过 ">3" 那条线判 0——腰部先不赚钱了，
        // 这才是这一维要报的顶部形态
        assertEquals("中位负反馈吹哨", TemperatureCalculator.premiumStructure(tiersOf("2:3.00", "4:9.99")));
    }

    // ---------- 量能：绝对七档，持平落在 18000 亿那一格 ----------

    @Test
    void volumeAbsoluteThresholds() {
        assertEquals(3, volume("30001"));
        assertEquals(2, volume("30000"));
        assertEquals(2, volume("25000"));
        assertEquals(1, volume("22001"));
        assertEquals(1, volume("20000"));
        assertEquals(1, volume("19000"));
        assertEquals(0, volume("18000"));
        assertEquals(-1, volume("17000"));
        assertEquals(-2, volume("16000"));
        assertEquals(-2, volume("15000"));
        assertEquals(-3, volume("10000"));
        assertEquals(-3, volume("9999"));
    }


    private static int volume(String totalVolume) {
        DailyRecord r = full();
        r.setTotalVolume(new BigDecimal(totalVolume));
        return TemperatureCalculator.calcVolumeScore(r).intValue();
    }

    // ---------- 第 4 维：炸板率 + 家数封板率 + 回封率，三分支各自出分再平均 ----------

    /** 09-04 那天：次数口径的炸板率已经判到最低，另两个家数口径说的是同一个故事。 */
    @Test
    void brokenDimAveragesThreeBranches() {
        TemperatureCalculator.BrokenDim dim =
                TemperatureCalculator.calcBrokenDim(broken("84.7"), poolsOf(39, 48, 21));
        // −3(炸板率 84.7%，>70% 那一格) + 0(家数封板 44.8%，落在 >=40 档) + 0(回封 30.4%，落在 >=30 档) → -1
        assertEquals(-1, dim.getScore().intValue());
        assertEquals(0, new BigDecimal("44.8").compareTo(dim.getSealedHomeRate()));
        assertEquals(0, new BigDecimal("30.4").compareTo(dim.getResealRate()));
    }

    @Test
    void brokenDimRenormalizesOverPresentBranches() {
        // 没回补盘面明细只等于"两个家数口径未知"，不等于"封板率确认为 0"
        assertEquals(-1, TemperatureCalculator.calcBrokenDim(broken("42.2"), poolsOf(0, 0, 0)).getScore().intValue());
        // 反过来：炸板率没填，家数两项都在满分档 → 3，不是被一个缺席项拖成 1
        DailyRecord noRate = broken(null);
        assertEquals(3, TemperatureCalculator.calcBrokenDim(noRate, poolsOf(90, 10, 80)).getScore().intValue());
        // 三项全缺才是整维未评
        assertNull(TemperatureCalculator.calcBrokenDim(noRate, poolsOf(0, 0, 0)).getScore());
    }

    /** 三套刻度里两套是按 14 天实测自造的（03 篇只有炸板率），算式必须原样摊在 hover 上供他否。 */
    @Test
    void brokenDimPrintsEveryFormula() {
        String note = TemperatureCalculator.calcBrokenDim(broken("84.7"), poolsOf(39, 48, 21)).getNote();
        // 09-04：只有次数口径那一条判到底（>70% → -3），两个家数口径都刚好踩在持平格上
        assertTrue(note.contains("炸板率(次数) 84.7% → -3 分"));
        assertTrue(note.contains("家数封板率 39÷(39+48)=44.8% → 0 分"));
        assertTrue(note.contains("回封率 21÷(21+48)=30.4% → 0 分"));
        assertTrue(note.contains("三分支平均 -1 → -1 分"));
        assertTrue(note.length() <= 300, "note 要落进 VARCHAR(300)");

        String partial = TemperatureCalculator.calcBrokenDim(broken("42.2"), poolsOf(0, 0, 0)).getNote();
        assertTrue(partial.contains("缺 2 项，按在场子项平均"));
    }

    /** 09-03：−2 + 1 + 0 → −0.33，取整回 0。平均不做这一步就会被读成"比在场子项都差"。 */
    @Test
    void brokenDimRoundsFractionalAverageAwayFromZero() {
        TemperatureCalculator.BrokenDim dim =
                TemperatureCalculator.calcBrokenDim(broken("65.1"), poolsOf(44, 33, 26));
        assertEquals(0, dim.getScore().intValue());
        assertTrue(dim.getNote().contains("三分支平均 -0.33 → 0 分"));
        // 三分支的分母是 3，永远踩不到 .5 这个平局；两分支才踩得到。
        // BigDecimal HALF_UP 往远离 0 的方向走，Math.round(-0.5) 会把它抬回 0——负档白开。
        assertEquals(-1, TemperatureCalculator.average(Arrays.asList(-1, 0)).intValue());
        assertEquals(1, TemperatureCalculator.average(Arrays.asList(1, 0)).intValue());
    }

    /** 三个子项都落进 record：卡片要能直接显示两个新率，不必再问后端要一次。 */
    @Test
    void brokenDimLandsOnTheRecord() {
        DailyRecord r = broken("84.7");
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), poolsOf(39, 48, 21));
        assertEquals(-1, r.getScoreBroken().intValue());
        assertEquals(0, new BigDecimal("44.8").compareTo(r.getSealedHomeRate()));
        assertEquals(0, new BigDecimal("30.4").compareTo(r.getResealRate()));
        assertTrue(r.getBrokenNote().contains("回封率"));
    }

    // ---------- 主线明确度：原文没有 2 分档 ----------

    @Test
    void themeHasSixTiers() {
        assertEquals(3, theme(3));
        assertEquals(2, theme(2));
        assertEquals(1, theme(1));
        assertEquals(0, theme(0));
        assertEquals(-1, theme(-1));
        assertEquals(-2, theme(-2));
        assertEquals(-3, theme(-3));
    }

    private static int theme(Integer scoreTheme) {
        DailyRecord r = full();
        r.setScoreTheme(scoreTheme);
        return TemperatureCalculator.calcThemeScore(r).intValue();
    }

    private static DailyRecord broken(String rate) {
        DailyRecord r = full();
        r.setBrokenBoardRate(rate == null ? null : new BigDecimal(rate));
        return r;
    }

    private static DailyRecord breadth(int up, int down) {
        DailyRecord r = new DailyRecord();
        r.setLimitUpCount(up);
        r.setLimitDownCount(down);
        return r;
    }

    /** 三池家数：第 4 维两个家数口径子项的唯一输入，0/0 表示那天没有明细。 */
    private static ScoreInputs poolsOf(int zt, int zb, int reseal) {
        PoolCounts counts = new PoolCounts();
        counts.setZtCount(zt);
        counts.setZbCount(zb);
        counts.setResealCount(reseal);
        ScoreInputs inputs = ScoreInputs.empty();
        inputs.setPoolCounts(counts);
        return inputs;
    }

    /** 只要"近期最高几板"的历史；量能照给，免得顺手把量能维的判据一起改了。 */
    private static List<DailyRecord> lowHistory(int board) {
        List<DailyRecord> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DailyRecord r = new DailyRecord();
            r.setTotalVolume(new BigDecimal("18000"));
            r.setMaxConsecutiveLimit(board);
            list.add(r);
        }
        return list;
    }

    // ---------- 阶段：七个都要可达 ----------

    @Test
    void allSevenStagesAreReachable() {
        Map<Double[], String> cases = new LinkedHashMap<>();
        cases.put(new Double[]{10.0, 12.0}, "冰点");
        cases.put(new Double[]{25.0, 25.0}, "修复");
        cases.put(new Double[]{40.0, 40.0}, "启动");
        cases.put(new Double[]{65.0, 65.0}, "发酵");
        cases.put(new Double[]{85.0, 85.0}, "高潮");
        cases.put(new Double[]{60.0, 80.0}, "分歧");
        cases.put(new Double[]{35.0, 60.0}, "退潮");

        Set<String> emitted = new HashSet<>();
        for (Map.Entry<Double[], String> entry : cases.entrySet()) {
            String actual = TemperatureCalculator.determineStage(entry.getKey()[0], entry.getKey()[1]);
            assertEquals(entry.getValue(), actual, "温度 " + entry.getKey()[0] + " 昨温 " + entry.getKey()[1]);
            emitted.add(actual);
        }
        assertEquals(7, emitted.size(), "七个阶段必须都能被判定出来");
    }

    /** 旧实现把静态档位铺满 (0,100)，退潮那一行永远走不到，跌停 118 家的那天被标成"启动·加仓进攻"。 */
    @Test
    void declineIsJudgedByMotionNotWaterLevel() {
        // 同样落在 12°，还在下杀途中是退潮、已经在底部横住才是冰点
        assertEquals("退潮", TemperatureCalculator.determineStage(12, 33.3));
        assertEquals("冰点", TemperatureCalculator.determineStage(12, 14.3));
        assertEquals("退潮", TemperatureCalculator.determineStage(38.1, 61.9));   // 08-19
        assertEquals("分歧", TemperatureCalculator.determineStage(57.1, 71.4));   // 09-02 龙头断板
        assertEquals("发酵", TemperatureCalculator.determineStage(66.7, 76.2));   // 温和回落不算转折
    }

    @Test
    void riseIsNeverMisreadAsDivergence() {
        assertEquals("发酵", TemperatureCalculator.determineStage(71.4, 52.4));
        assertEquals("高潮", TemperatureCalculator.determineStage(85.7, 71.4));
    }

    @Test
    void firstRecordEverFallsBackToWaterLevelOnly() {
        assertEquals("启动", TemperatureCalculator.determineStage(44.4, null));
        assertEquals("横盘", TemperatureCalculator.determineDirection(44.4, null));
    }

    // ---------- 大面数档位 ----------

    /** 大面数六档：0=3, 1~3=2, 4~7=1, 8~12=-1, 13~20=-2, 21 以上=-3。压线的四个边界各留一个。 */
    @Test
    void lossTiers() {
        for (int[] pair : new int[][]{{0, 3}, {1, 2}, {3, 2}, {4, 1}, {7, 1}, {8, -1}, {12, -1},
                {13, -2}, {20, -2}, {21, -3}, {100, -3}}) {
            DailyRecord r = full();
            r.setBigLossCount(pair[0]);
            assertEquals(pair[1], TemperatureCalculator.calcLossScore(r).intValue(), "loss " + pair[0]);
        }
    }

    private static int dim(DailyRecord r) {
        return r.getScoredDims() == null ? -1 : r.getScoredDims().intValue();
    }

    private static int total(DailyRecord r) {
        return r.getTotalScore() == null ? -1 : r.getTotalScore().intValue();
    }
}
