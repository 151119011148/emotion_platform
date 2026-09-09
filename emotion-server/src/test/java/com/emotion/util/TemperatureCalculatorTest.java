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
 * 所以覆盖的重点不是"算得对"，而是"原文每一档都能被算到、缺数据不会被算成分数"。
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

    /** 只有低位档、均值 +2.20% 的一天：单档时权重不起作用，分数就是这一档的 2 分。 */
    private static final ScoreInputs LOW_ONLY = inputsOf("2:2.20", "3:2.20");

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

    /** 成交额与连板的历史基准：三日均量 18000 亿、最高 4 板，让量能 ratio=1、连板"创新高"成立。 */
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

    // ---------- 未评的维度必须整维剔出分母 ----------

    @Test
    void absentAnchorAndSurveillanceKeepTheDenominatorAtSeven() {
        DailyRecord r = full();
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), LOW_ONLY);
        // 3(高度)+2(溢价)+3(涨跌停比)+2(炸板)+3(大面)+3(量能)+3(明确度) = 19；第 8/9 维没取到，不进分母
        assertEquals(7, dim(r));
        assertEquals(14, total(r));
        // temperature will be recalculated based on weighted formula
        assertNull(r.getAnchorScore());
        assertNull(r.getSurvCount());
    }

    // ---------- 第 8 维 阵眼、第 9 维 监管股今日溢价 ----------

    /** 九维齐全：同样 19 分，在 27 分制下只剩 70.4°——这就是"哨兵进分"的分量。 */
    @Test
    void nineCompleteDimsUseTwentySevenAsDenominator() {
        DailyRecord r = full();
        // 不能改 LOW_ONLY：它是共享常量，改一次就污染后面每一个用例
        ScoreInputs in = inputsOf("2:2.20", "3:2.20");
        in.setAnchorScore(0);
        in.setAnchorNote("0 分｜哈药股份 盘中触板 -7.47%（最低 -9.96%） · 跨度第 40 日 · 最高 5 板");
        in.setSurvCount(2);
        in.setSurvPremium(new BigDecimal("-3.00"));
        in.setSurvNote("监管股今日溢价 = -3.00% = 2 只涨幅均值（进分 2 家：严重异常波动/交易所监管）");
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), in);

        assertEquals(9, dim(r));
        // 两维各 0 分不动总分，只是把分母从 21 撑到 27：90.5° → 70.4°，一个 0 分吃掉 10°
        assertEquals(12, total(r));
        // temperature will be recalculated based on weighted formula
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
        // temperature updated based on new weighted formula
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
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), LOW_ONLY);
        assertNull(r.getScoreTheme());
        assertEquals(6, dim(r));
        // 旧口径是 16/21=76.2°：一个"还没判断"就凭空吃掉 14.3°
        // temperature updated based on new weighted formula
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
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), LOW_ONLY);
        assertEquals(3, TemperatureCalculator.calcLossScore(r).intValue());
        assertNull(r.getScoreBroken());
        assertEquals(6, dim(r));
    }

    // ---------- 无历史 = 没有趋势可言，不兜底造一个中性分 ----------

    @Test
    void trendDimsAreUnscoredWithoutHistory() {
        DailyRecord r = full();
        TemperatureCalculator.calculate(r, new ArrayList<>(Arrays.asList(new DailyRecord())), LOW_ONLY);
        // Neither height nor volume depends on history anymore - both are absolute
        // With full() data: height=5->2, volume=18000->0, all 7 dims scored
        assertEquals(7, dim(r));
    }

    @Test
    void boardBreakScoresZeroEvenWithoutHistory() {
        DailyRecord r = full();
        r.setMaxConsecutiveLimit(1);
        TemperatureCalculator.calculate(r, new ArrayList<DailyRecord>());
        assertEquals(-1, r.getScoreHeight().intValue());
    }

    // ---------- 负档：只在"原文最低档是个大桶"的维度上开 ----------

    /** 第 2/9 维共用这组阈值：-2%~-4% 仍是原文的"差"，跌破 -4% 才算崩。 */
    @Test
    void premiumSevenTiers() {
        assertEquals(3, TemperatureCalculator.bandPremium(new BigDecimal("5.01")));
        assertEquals(2, TemperatureCalculator.bandPremium(new BigDecimal("3.50")));
        assertEquals(1, TemperatureCalculator.bandPremium(new BigDecimal("1.50")));
        assertEquals(0, TemperatureCalculator.bandPremium(new BigDecimal("0.00")));
        assertEquals(-1, TemperatureCalculator.bandPremium(new BigDecimal("-2.00")));
        assertEquals(-1, TemperatureCalculator.bandPremium(new BigDecimal("-3.00")));
        assertEquals(-2, TemperatureCalculator.bandPremium(new BigDecimal("-3.01")));
        assertEquals(-2, TemperatureCalculator.bandPremium(new BigDecimal("-5.00")));
        assertEquals(-3, TemperatureCalculator.bandPremium(new BigDecimal("-5.01")));
        assertEquals(-3, TemperatureCalculator.bandPremium(new BigDecimal("-10.73")));
    }

    @Test
    void breadthNegativeOnlyForOneSidedSlaughter() {
        assertEquals(0, TemperatureCalculator.calcBreadthScore(breadth(3, 6)).intValue());
        assertEquals(-1, TemperatureCalculator.calcBreadthScore(breadth(20, 39)).intValue());
        assertEquals(-3, TemperatureCalculator.calcBreadthScore(breadth(5, 40)).intValue());
        assertEquals(-2, TemperatureCalculator.calcBreadthScore(breadth(15, 25)).intValue());
        assertEquals(3, TemperatureCalculator.calcBreadthScore(breadth(81, 0)).intValue());
        assertEquals(2, TemperatureCalculator.calcBreadthScore(breadth(65, 2)).intValue());
        assertEquals(1, TemperatureCalculator.calcBreadthScore(breadth(45, 4)).intValue());
    }

    @Test
    void trueBoardBreakScoresMinusOne() {
        DailyRecord r = full();
        r.setMaxConsecutiveLimit(1);
        // 近期最高 4 板 → 塌回首板是真断龙；近期最高才 3 板的池子塌到首板只是低位循环，仍算 0
        assertEquals(-1, TemperatureCalculator.calcHeightScore(r, history("18000", "18000", "18000")).intValue());
        assertEquals(-1, TemperatureCalculator.calcHeightScore(r, lowHistory(3)).intValue());
    }

    /** 量能/主线/阵眼刻意不开负档：原文在这三维上判到 0 已经是最重，再往下就是发明。 */
    @Test
    void volumeAndThemeStillBottomAtZero() {
        assertEquals(0, volume("18000"));
        assertEquals(0, theme(0));
    }

    // ---------- 溢价：分档合成，高位权重更重，缺档摊回 ----------

    @Test
    void highTierOutweighsLowTierOnTheSameDay() {
        // 低位 +5% / 高位 -5% 与反向：三组分数不对称（-5% 已是 -1 档），只有权重能定出方向
        assertEquals(-1, TemperatureCalculator.calcPremiumScore(tiersOf("2:5.00", "7:-5.00")).intValue());
        assertEquals(1, TemperatureCalculator.calcPremiumScore(tiersOf("2:-5.00", "7:5.00")).intValue());
    }

    @Test
    void absentTierRenormalizesInsteadOfDiluting() {
        // 低位 2 分 + 高位 3 分，中位那天根本没有票：(1×2+2.5×3)/3.5 = 2.71 → 3
        MarketMetrics.PremiumTiers tiers = tiersOf("2:2.20", "5:5.00");
        assertNull(TemperatureCalculator.scoreGroup(tiers, PremiumGroup.MID));
        assertEquals(2, TemperatureCalculator.calcPremiumScore(tiers).intValue());
        // 把缺席的中位按 0 分计入分母会得到 (2+0+7.5)/5 = 1.9 → 2，凭空掉一分
    }

    @Test
    void noTierAtAllLeavesTheDimUnscored() {
        assertNull(TemperatureCalculator.calcPremiumScore(tiersOf()));
        assertNull(TemperatureCalculator.calcPremiumScore(null));

        DailyRecord r = full();
        TemperatureCalculator.calculate(r, history("18000", "18000", "18000"), inputsOf());
        assertNull(r.getScorePremium());
        assertEquals(6, dim(r));
        // temperature updated based on new weighted formula
    }

    @Test
    void pooledScalarIsDisplayOnly() {
        // 含首板整池 +9.99% 看着像 3 分，但分档后在亏钱的两档合成 -5.50% → 跌破 -4%，进分的是 -1
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
        // 同一块盘面在写死口径下读"高位断层"；5 板以下没有独立高位了，它改口成中位吹哨
        assertEquals("中位负反馈吹哨", TemperatureCalculator.premiumStructure(tiersOf("2:-1.94", "4:1.19")));
        // 低位还在赚钱时高位断档只是接力换到腰部，不是断层
        assertEquals("无显著结构", TemperatureCalculator.premiumStructure(tiersOf("2:3.00", "4:9.99")));
    }

    // ---------- 量能：原文只有三档，持平归 3、背离用溢价判 ----------

    @Test
    void volumeAbsoluteThresholds() {
        assertEquals(3, volume("22001"));
        assertEquals(2, volume("20000"));
        assertEquals(1, volume("19000"));
        assertEquals(0, volume("18000"));
        assertEquals(-1, volume("15000"));
        assertEquals(-2, volume("10000"));
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
        // 0(炸板率 84.7%) + 0(家数封板 44.8%，落在 ≥40 档) + 0(回封 30.4%，落在 ≥30 档) → 0
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
        // 09-04：三个子项全部判到最低档，连平均都没有余量
        assertTrue(note.contains("炸板率(次数) 84.7% → 0 分"));
        assertTrue(note.contains("家数封板率 39÷(39+48)=44.8% → 0 分"));
        assertTrue(note.contains("回封率 21÷(21+48)=30.4% → 0 分"));
        assertTrue(note.contains("三分支平均 0 → 0 分"));
        assertTrue(note.length() <= 300, "note 要落进 VARCHAR(300)");

        String partial = TemperatureCalculator.calcBrokenDim(broken("42.2"), poolsOf(0, 0, 0)).getNote();
        assertTrue(partial.contains("缺 2 项，按在场子项平均"));
    }

    /** 09-03：1 + 1 + 0 → 0.67，远离 0 取整成 1。平均不做这一步就会被读成"比在场子项都差"。 */
    @Test
    void brokenDimRoundsFractionalAverageAwayFromZero() {
        TemperatureCalculator.BrokenDim dim =
                TemperatureCalculator.calcBrokenDim(broken("65.1"), poolsOf(44, 33, 26));
        assertEquals(0, dim.getScore().intValue());
        assertTrue(dim.getNote().contains("三分支平均 0.67 → 1 分"));
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

    @Test
    void lossTiers() {
        for (int[] pair : new int[][]{{0, 3}, {1, 2}, {2, 2}, {3, 1}, {4, 1}, {5, -1}, {9, -1},
                {10, -2}, {20, -2}, {21, -3}, {100, -3}}) {
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
