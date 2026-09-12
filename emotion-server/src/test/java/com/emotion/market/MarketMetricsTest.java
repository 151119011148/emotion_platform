package com.emotion.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 打分口径的边界。这几个数直接决定温度计读数，档位判错的代价比少一个字段大得多。 */
class MarketMetricsTest {

    @Test
    void brokenRateUsesBreakCountNotHomeCount() {
        // 48 家炸板、共炸 216 次、封住 39 家
        assertEquals(0, new BigDecimal("84.7")
                .compareTo(MarketMetrics.brokenRate(216, 39)));
        assertEquals(0, new BigDecimal("55.2")
                .compareTo(MarketMetrics.percent(48, 48 + 39)));
    }

    @Test
    void brokenRateIsZeroWhenNothingBroke() {
        assertEquals(0, new BigDecimal("0.0").compareTo(MarketMetrics.brokenRate(0, 39)));
    }

    /** 当天既没涨停也没炸板：返回 null 而不是 0，交由上层决定怎么记，避免把"没有数据"写成"零炸板"。 */
    @Test
    void brokenRateIsNullWhenThereIsNoSampleAtAll() {
        assertNull(MarketMetrics.brokenRate(0, 0));
    }

    @Test
    void roundsHalfUpToOneDecimal() {
        assertEquals(0, new BigDecimal("33.3").compareTo(MarketMetrics.brokenRate(1, 2)));
        assertEquals(0, new BigDecimal("16.7").compareTo(MarketMetrics.brokenRate(1, 5)));
    }

    @Test
    void percentGuardsWithoutDividingByZero() {
        assertEquals(0, new BigDecimal("0.0").compareTo(MarketMetrics.percent(0, 0)));
    }

    @Test
    void breakCountSkipsMissingZbc() {
        List<PoolRow> rows = Arrays.asList(broken("A", 100, 90, -5, 3), broken("B", 100, 90, -5, null));

        assertEquals(3, MarketMetrics.breakCount(rows));
    }

    /** 回撤 >7% 且收盘绿盘，两个条件缺一不可。 */
    @Test
    void bigLossNeedsBothPullbackAndGreenClose() {
        List<PoolRow> rows = Arrays.asList(
                broken("深回撤绿盘", 100, 87, -3, 1),   // 回撤 13%、收绿 → 算
                broken("深回撤红盘", 100, 87, 2, 1),    // 回撤 13% 但仍红盘 → 不算
                broken("浅回撤绿盘", 100, 95, -3, 1),   // 回撤 5% → 不算
                broken("恰好7", 100, 93, -3, 1),        // 回撤 7%，判据是严格大于 → 不算
                broken("缺涨停价", 0, 90, -3, 1));      // 无法判断 → 计入 unusable

        MarketMetrics.BigLoss result = MarketMetrics.bigLoss(rows);
        assertEquals(1, result.getCount());
        assertEquals(1, result.getUnusable());
    }

    @Test
    void pullbackIsRatioSoThe1000xScaleCancels() {
        assertEquals(0, new BigDecimal("11.60")
                .compareTo(broken("x", 100000, 88400, -2.7, 1).pullbackFromLimitPct()));
        assertNull(broken("x", 0, 88400, -2.7, 1).pullbackFromLimitPct());
    }

    /** 1 进 2 大面：炸板 big_loss 与收盘跌 >7% 两条路并集，−7% 整按严格不等不判，无价不能按 0 判。 */
    @Test
    void firstToTwoBigLossUnionsBrokenFlagAndDeepClose() {
        assertTrue(MarketMetrics.firstToTwoBigLoss(true, new BigDecimal("-1.9")));   // 冲板被砸
        assertTrue(MarketMetrics.firstToTwoBigLoss(false, new BigDecimal("-7.01"))); // 未触板深水
        assertTrue(MarketMetrics.firstToTwoBigLoss(false, new BigDecimal("-10")));   // 跌停
        assertFalse(MarketMetrics.firstToTwoBigLoss(false, new BigDecimal("-7.00")));// 恰好 7%
        assertFalse(MarketMetrics.firstToTwoBigLoss(false, new BigDecimal("-5.95")));
        assertFalse(MarketMetrics.firstToTwoBigLoss(false, null));                   // 无报价≠没亏
        assertFalse(MarketMetrics.firstToTwoBigLoss(false, new BigDecimal("3.19")));
    }

    @Test
    void premiumAveragesOnlyMatchedQuotesAndReportsSampleSize() {
        List<PoolRow> prevPool = Arrays.asList(limitUp("600000", 1), limitUp("002909", 0), limitUp("601999", 1));
        Map<String, TencentClient.StockQuote> quotes = new HashMap<>();
        quotes.put("sh600000", quote("sh600000", "1.73"));
        quotes.put("sz002909", quote("sz002909", "-9.99"));
        // sh601999 没有报价（停牌或上游没给）

        MarketMetrics.Premium premium = MarketMetrics.premium(prevPool, quotes);

        assertEquals(2, premium.getMatched());
        assertEquals(0, new BigDecimal("-4.13").compareTo(premium.getValue()));
    }

    @Test
    void premiumIsAbsentRatherThanZeroWhenNothingMatches() {
        MarketMetrics.Premium premium = MarketMetrics.premium(
                Collections.singletonList(limitUp("600000", 1)),
                Collections.<String, TencentClient.StockQuote>emptyMap());

        assertEquals(0, premium.getMatched());
        assertNull(premium.getValue());
    }

    // ---------- 连板档溢价 ----------

    /**
     * 真实存档：09-04 涨停池 39 家里 32 家是首板，只有 6 只 2 板和 1 只 5 板进档。
     * 首板必须被排除在外（使用者的口径），且排除掉的量要如实报出来，
     * 否则"覆盖 7/39"看起来像漏抓了一半。
     */
    @Test
    void tiersExcludeFirstBoardFromRealPoolAndReportTheExcludedCount() {
        List<PoolRow> prevPool = new EastmoneyClient(null, "", "", "", "", 200)
                .parsePool(Fixtures.text("pool_limit_up.json")).getRows();
        Map<String, BigDecimal> pct = new HashMap<>();
        pct.put("605398", dec("3.10"));
        pct.put("605580", dec("-1.20"));
        pct.put("605577", dec("10.01"));
        pct.put("002403", dec("2.40"));
        pct.put("600108", dec("-4.00"));
        pct.put("603162", dec("0.60"));
        pct.put("600865", dec("5.20"));

        MarketMetrics.PremiumTiers tiers = MarketMetrics.premiumTiers(prevPool, pct);

        assertEquals(32, tiers.getFirstBoard());
        assertEquals(0, tiers.getNoBoard());
        assertEquals(7, tiers.getConsidered());
        assertEquals(7, tiers.getMatched());
        assertEquals(Arrays.asList(2, 5), boardsOf(tiers));
        // 活的界线：当天最高 5 板 → M=3 → 5 板就是高位（写死口径下它是中位），中位档反而空出来
        assertEquals(5, tiers.maxBoard());
        assertEquals(0, dec("1.02").compareTo(tiers.group(PremiumGroup.LOW).getAvgPct()));
        assertTrue(tiers.group(PremiumGroup.MID).isEmpty());
        assertNull(tiers.group(PremiumGroup.MID).getAvgPct());
        assertEquals(0, dec("10.01").compareTo(tiers.group(PremiumGroup.HIGH).getAvgPct()));
    }

    /** 8 及以上合并成一档：9 板和 12 板都进 board=8，拆细只是给个位数样本造噪声。 */
    @Test
    void boardsAtOrAboveEightCollapseIntoOneTier() {
        MarketMetrics.PremiumTiers tiers = MarketMetrics.premiumTiers(
                Arrays.asList(limitUp("600001", 9), limitUp("600002", 12), limitUp("600003", 8)),
                pct("600001", "10.00", "600002", "-2.00", "600003", "4.00"));

        assertEquals(Collections.singletonList(8), boardsOf(tiers));
        assertEquals(3, tiers.getConsidered());
        // (10 - 2 + 4) / 3 = 4.00，按样本合并而不是按档均值再平均
        assertEquals(0, dec("4.00").compareTo(tiers.group(PremiumGroup.HIGH).getAvgPct()));
    }

    /** 组均值是"组内全部样本的均值"：跨档合并，不做二次平均。 */
    @Test
    void groupMeanPoolsEverySampleInIt() {
        MarketMetrics.PremiumTiers tiers = MarketMetrics.premiumTiers(
                Arrays.asList(limitUp("600001", 2), limitUp("600002", 2), limitUp("600003", 3)),
                pct("600001", "4.00", "600002", "2.00", "600003", "-5.00"));

        // 当天最高 3 板 → M=2 → 2、3 板同属中位组；跨档合并的口径没变，只是组名跟着界线走了
        assertEquals(0, dec("0.33").compareTo(tiers.group(PremiumGroup.MID).getAvgPct()));
        // 逐档仍各自留底，卡片上 2板/3板要分开显示
        assertEquals(0, dec("3.00").compareTo(tierAt(tiers, 2).getAvgPct()));
        assertEquals(0, dec("-5.00").compareTo(tierAt(tiers, 3).getAvgPct()));
    }

    /** 停牌、北交所无报价：家数照记、matched 缩水。均值只代表取到价的那部分，不能冒充全档。 */
    @Test
    void missingQuotesShrinkMatchedButNotStockCount() {
        MarketMetrics.PremiumTiers tiers = MarketMetrics.premiumTiers(
                Arrays.asList(limitUp("600001", 2), limitUp("600002", 2), limitUp("920066", 2)),
                pct("600001", "4.00", "600002", "2.00"));

        MarketMetrics.TierPremium two = tierAt(tiers, 2);
        assertEquals(3, two.getStockCount());
        assertEquals(2, two.getMatched());
        assertEquals(0, dec("3.00").compareTo(two.getAvgPct()));
        // 组聚合同样分开记两个数：家数是盘面事实，matched 是样本事实
        // 整池最高就是 2 板 → M=2 → 这一组是中位而不是低位
        assertEquals(3, tiers.group(PremiumGroup.MID).getStockCount());
        assertEquals(2, tiers.group(PremiumGroup.MID).getMatched());
    }

    /** 整档一只价都没有：出 null 出告警，绝不出 0——0 会被读成"这档今天不涨不跌"。 */
    @Test
    void tierWithoutAnyQuoteIsNullNotZero() {
        MarketMetrics.PremiumTiers tiers = MarketMetrics.premiumTiers(
                Collections.singletonList(limitUp("600001", 4)),
                Collections.<String, BigDecimal>emptyMap());

        assertNull(tierAt(tiers, 4).getAvgPct());
        assertEquals(0, tierAt(tiers, 4).getMatched());
        assertTrue(tiers.group(PremiumGroup.MID).isEmpty());
        assertEquals(1, tiers.getWarnings().size());
    }

    /** 越界只丢档、不丢整日：脏票可能正是当天唯一的高位样本，但同日低位信号不该被它一起带走。 */
    @Test
    void dirtyTierMeanIsDroppedWithAWarning() {
        MarketMetrics.PremiumTiers tiers = MarketMetrics.premiumTiers(
                Arrays.asList(limitUp("600001", 6), limitUp("600002", 2)),
                pct("600001", "25.00", "600002", "3.00"));

        assertTrue(tiers.getWarnings().get(0).contains("超出 ±20%"));
        assertEquals(1, tierAt(tiers, 6).getStockCount());
        assertEquals(0, tierAt(tiers, 6).getMatched());
        assertNull(tiers.group(PremiumGroup.HIGH).getAvgPct());
        // 低位档不受影响
        assertEquals(0, dec("3.00").compareTo(tiers.group(PremiumGroup.LOW).getAvgPct()));
    }

    /** 北交所一天 30%：920895 的 +29.97% 是它自己的涨停（08-28 实测），按 ±20% 丢就是把真信号当脏值。 */
    @Test
    void tierBoundFollowsTheBoardOfItsMembers() {
        MarketMetrics.PremiumTiers beijing = MarketMetrics.premiumTiers(
                Collections.singletonList(limitUp("920895", 2)), pct("920895", "29.97"));

        assertTrue(beijing.getWarnings().isEmpty());
        assertEquals(0, dec("29.97").compareTo(tierAt(beijing, 2).getAvgPct()));

        MarketMetrics.PremiumTiers mainBoard = MarketMetrics.premiumTiers(
                Collections.singletonList(limitUp("600895", 2)), pct("600895", "29.97"));

        assertTrue(mainBoard.getWarnings().get(0).contains("超出 ±20%"));
        assertNull(tierAt(mainBoard, 2).getAvgPct());
    }

    /** 极值只在有样本时才有意义；均值之外还要能给出"这档里最好/最差那只"。 */
    @Test
    void tierKeepsMaxAndMinOfItsMatchedSamples() {
        MarketMetrics.PremiumTiers tiers = MarketMetrics.premiumTiers(
                Arrays.asList(limitUp("600001", 3), limitUp("600002", 3)),
                pct("600001", "7.50", "600002", "-9.98"));

        assertEquals(0, dec("7.50").compareTo(tierAt(tiers, 3).getMaxPct()));
        assertEquals(0, dec("-9.98").compareTo(tierAt(tiers, 3).getMinPct()));
    }

    /** 昨日池为空（没拉到）时不能凭空造出档位：三组全是未评。 */
    @Test
    void emptyPrevPoolYieldsNoTiersAtAll() {
        MarketMetrics.PremiumTiers tiers = MarketMetrics.premiumTiers(
                Collections.<PoolRow>emptyList(), Collections.<String, BigDecimal>emptyMap());

        assertTrue(tiers.getTiers().isEmpty());
        assertEquals(0, tiers.getConsidered());
        for (PremiumGroup group : PremiumGroup.values()) {
            assertNull(tiers.group(group).getAvgPct());
        }
    }

    /** 连板数缺失的行单独立数，不混进首板：正常上游恒为 0，一旦不为 0 就是上游改字段了。 */
    @Test
    void rowsWithoutBoardNumberAreCountedSeparately() {
        PoolRow noLbc = new PoolRow();
        noLbc.setCode("600001");
        noLbc.setMarket(1);

        MarketMetrics.PremiumTiers tiers = MarketMetrics.premiumTiers(
                Arrays.asList(noLbc, limitUp("600002", 1)),
                pct("600001", "10.00", "600002", "10.00"));

        assertEquals(1, tiers.getNoBoard());
        assertEquals(0, tiers.getConsidered());
    }

    /** H=8 时活的界线和 03 篇里写死的三档完全重合——这条是"没改口径"的证明。 */
    @Test
    void groupBoundariesFollowTheTradersReading() {
        assertEquals(PremiumGroup.LOW, PremiumGroup.of(2, 8));
        assertEquals(PremiumGroup.LOW, PremiumGroup.of(3, 8));
        assertEquals(PremiumGroup.MID, PremiumGroup.of(4, 8));
        assertEquals(PremiumGroup.MID, PremiumGroup.of(5, 8));
        assertEquals(PremiumGroup.HIGH, PremiumGroup.of(6, 8));
        assertEquals(PremiumGroup.HIGH, PremiumGroup.of(8, 8));
    }

    /**
     * 中位线 = 前一天最高板的一半。H=5 那几天里 5 板是当轮顶端，必须读成高位，
     * 否则"高位抱团"这个判据在整个低周期里一次都不会亮。
     */
    @Test
    void midLineIsHalfOfYesterdaysTopBoard() {
        assertEquals(3, PremiumGroup.midLine(5));
        assertEquals(PremiumGroup.LOW, PremiumGroup.of(2, 5));
        assertEquals(PremiumGroup.MID, PremiumGroup.of(3, 5));
        assertEquals(PremiumGroup.MID, PremiumGroup.of(4, 5));
        assertEquals(PremiumGroup.HIGH, PremiumGroup.of(5, 5));
        assertEquals("高位(5板+)", PremiumGroup.HIGH.label(5));
        assertEquals("中位(3-4板)", PremiumGroup.MID.label(5));

        // 一半不足 2 时夹回 2：当天最高才 3~4 板，2 板就是中位，低档在结构上是空的
        assertEquals(2, PremiumGroup.midLine(3));
        assertEquals(2, PremiumGroup.midLine(2));
        assertEquals(PremiumGroup.MID, PremiumGroup.of(2, 4));
        assertEquals(PremiumGroup.HIGH, PremiumGroup.of(4, 4));
        assertEquals("低位(当天无此档)", PremiumGroup.LOW.label(4));
    }

    private static List<Integer> boardsOf(MarketMetrics.PremiumTiers tiers) {
        List<Integer> boards = new ArrayList<>();
        for (MarketMetrics.TierPremium tier : tiers.getTiers()) {
            boards.add(tier.getBoard());
        }
        return boards;
    }

    private static MarketMetrics.TierPremium tierAt(MarketMetrics.PremiumTiers tiers, int board) {
        for (MarketMetrics.TierPremium tier : tiers.getTiers()) {
            if (tier.getBoard() == board) {
                return tier;
            }
        }
        throw new AssertionError("没有 " + board + " 板档，实际档位=" + boardsOf(tiers));
    }

    // ---------- 第 9 维：在列监管股的今日溢价 ----------

    @Test
    void survivalMeanIsArithmeticOverInListStocks() {
        MarketMetrics.Survival survival = MarketMetrics.survivalPremium(Arrays.asList(
                member("600664", dec("10.00")), member("605577", dec("-7.47"))));

        // (10 - 7.47) / 2 = 1.265 → 1.27：均值不给任何一只票加权，人群本身就是口径
        assertEquals(0, dec("1.27").compareTo(survival.getAvgPct()));
        assertEquals(2, survival.getCount());
        assertEquals(2, survival.getMatched());
    }

    /**
     * 脏值守卫必须逐只过。在列常常只有三五只，一个 -99% 的错值能把
     * 一个本该 3 分的读数直接打到 0 分——只校最终标量挡不住这种情况。
     */
    @Test
    void survivalDropsDirtyValuesPerStockAndSaysSo() {
        MarketMetrics.Survival survival = MarketMetrics.survivalPremium(Arrays.asList(
                member("600664", dec("-99.00")), member("605577", dec("5.00")), member("002909", dec("3.00"))));

        assertEquals(0, dec("4.00").compareTo(survival.getAvgPct()));
        assertEquals(3, survival.getCount());
        assertEquals(2, survival.getMatched());
        assertEquals(1, survival.getDropped());
    }

    /** 同一道守卫按板块取上限：920895 在 09-01 的 +29.98% 是它自己的涨停，实测被 ±20% 误丢过一次。 */
    @Test
    void survivalBoundFollowsTheBoardOfEachStock() {
        MarketMetrics.Survival beijing = MarketMetrics.survivalPremium(Arrays.asList(
                member("920895", dec("29.98")), member("605577", dec("1.00"))));

        assertEquals(2, beijing.getMatched());
        assertEquals(0, beijing.getDropped());
        assertEquals(0, dec("15.49").compareTo(beijing.getAvgPct()));

        MarketMetrics.Survival mainBoard = MarketMetrics.survivalPremium(
                Arrays.asList(member("600664", dec("29.98")), member("605577", dec("1.00"))));

        assertEquals(1, mainBoard.getMatched());
        assertEquals(1, mainBoard.getDropped());
        assertEquals(0, dec("1.00").compareTo(mainBoard.getAvgPct()));
    }

    /** 停牌/没取到价的：算在人群里（在列家数是真的），但不进样本，均值不能假装它有数。 */
    @Test
    void survivalCountsStocksWithoutPriceButDoesNotAverageThem() {
        MarketMetrics.Survival survival = MarketMetrics.survivalPremium(Arrays.asList(
                member("600664", null), member("605577", dec("-2.00"))));

        assertEquals(2, survival.getCount());
        assertEquals(1, survival.getMatched());
        assertEquals(0, dec("-2.00").compareTo(survival.getAvgPct()));
    }

    /** 0 家与"从没拉过"是两个结论：这里交出 count=0 且均值为 null，落库才分得出 0 与 NULL。 */
    @Test
    void emptySurveillanceWindowHasNoSampleRatherThanZeroPercent() {
        MarketMetrics.Survival survival = MarketMetrics.survivalPremium(new ArrayList<SurvivalMember>());

        assertEquals(0, survival.getCount());
        assertNull(survival.getAvgPct());
    }

    private static SurvivalMember member(String code, BigDecimal pct) {
        SurvivalMember member = new SurvivalMember();
        member.setCode(code);
        member.setName(code);
        member.setPct(pct);
        return member;
    }

    private static Map<String, BigDecimal> pct(String... codeAndPct) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (int i = 0; i < codeAndPct.length; i += 2) {
            map.put(codeAndPct[i], new BigDecimal(codeAndPct[i + 1]));
        }
        return map;
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static PoolRow broken(String name, long limitPrice, long price, double changePct, Integer breaks) {
        PoolRow row = new PoolRow();
        row.setName(name);
        row.setLimitPrice(BigDecimal.valueOf(limitPrice));
        row.setPrice(BigDecimal.valueOf(price));
        row.setZdp(BigDecimal.valueOf(changePct));
        row.setZbc(breaks);
        return row;
    }

    private static PoolRow limitUp(String code, int lbc) {
        PoolRow row = new PoolRow();
        row.setCode(code);
        row.setMarket(code.startsWith("6") || code.startsWith("9") ? 1 : 0);
        row.setLbc(lbc);
        return row;
    }

    private static TencentClient.StockQuote quote(String symbol, String changePct) {
        TencentClient.StockQuote quote = new TencentClient.StockQuote();
        quote.setSymbol(symbol);
        quote.setCode(symbol.substring(2));
        quote.setChangePct(new BigDecimal(changePct));
        return quote;
    }
}
