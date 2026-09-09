package com.emotion.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.emotion.entity.DailyRecord;
import com.emotion.market.MarketMetrics;
import com.emotion.market.PoolCounts;

/**
 * 子项人工覆盖叠到自动读数上这件事，本身只有三条承诺：
 * <b>没填就一个字都不动</b>、<b>填了只动那一格</b>、<b>动过以后依据串说的是真话</b>。
 *
 * <p>第一条最要紧，也最容易被"顺手加个默认值"破掉：{@code recalcAll} 是全量跑的，
 * coalesce 一旦把 NULL 读成 0，全部历史日子的温度一起换尺子，而这里没有 git 可回退。
 * 真实日子的逐列基线在 {@code RecalcGoldenTest}，这里守的是那条规则本身。
 */
class ManualOverrideTest {

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    /** 盘面明细算出来 39÷(39+48)=44.8% 封板、21÷(21+48)=30.4% 回封，炸板率 84.7% —— 09-04 那天。 */
    private static ScoreInputs pools(int zt, int zb, int reseal) {
        PoolCounts counts = new PoolCounts();
        counts.setZtCount(zt);
        counts.setZbCount(zb);
        counts.setResealCount(reseal);
        ScoreInputs in = ScoreInputs.empty();
        in.setPoolCounts(counts);
        return in;
    }

    private static DailyRecord rate(String brokenBoardRate) {
        DailyRecord r = new DailyRecord();
        r.setBrokenBoardRate(brokenBoardRate == null ? null : n(brokenBoardRate));
        return r;
    }

    // ---------- pick 的规则：NULL 才是没填，0 是一个读数 ----------

    @Test
    void onlyNullCountsAsNotFilled() {
        assertEquals(n("1"), ManualOverride.pick(null, n("1")));
        assertEquals(n("0"), ManualOverride.pick(n("0"), n("1")));
        assertEquals(Integer.valueOf(0), ManualOverride.pick(0, 5));
        assertFalse(ManualOverride.used(null));
        assertTrue(ManualOverride.used(0));
    }

    /** 第 8 维的分是唯一直接进分子的覆盖值，越界会把温度推出 0~100 这把尺子。 */
    @Test
    void anchorScoreClampsIntoTheBand() {
        assertEquals(Integer.valueOf(3), ManualOverride.clampScore(9));
        assertEquals(Integer.valueOf(-3), ManualOverride.clampScore(-5));
        assertEquals(Integer.valueOf(-2), ManualOverride.clampScore(-2));
        assertEquals(Integer.valueOf(2), ManualOverride.clampScore(2));
        assertNull(ManualOverride.clampScore(null));
    }

    // ---------- 第 4 维 ----------

    /** 手改一格封板率：这一支按人工值出分，另外两支仍按明细出分，整维平均跟着动。 */
    @Test
    void manualSealedRateReplacesOnlyItsOwnBranch() {
        DailyRecord auto = rate("84.7");
        TemperatureCalculator.BrokenDim base = TemperatureCalculator.calcBrokenDim(auto, pools(39, 48, 21));

        DailyRecord manual = rate("84.7");
        manual.setManualSealedHomeRate(n("90"));
        TemperatureCalculator.BrokenDim dim = TemperatureCalculator.calcBrokenDim(manual, pools(39, 48, 21));

        assertEquals(-1, base.getScore().intValue());
        // 90% 封板落在 ≥80 档=3 分：(-3 炸板率 + 3 封板 + 0 回封)/3 = 0
        assertEquals(0, dim.getScore().intValue());
        assertEquals(0, n("90").compareTo(dim.getSealedHomeRate()));
        assertEquals(0, n("30.4").compareTo(dim.getResealRate()));
    }

    /**
     * 人工那一支的算式必须整条换掉：明细里的 39÷(39+48) 算不出 90%，
     * 印在一起等于用真算式给假数背书。自动那两支的算式照旧要留着。
     */
    @Test
    void manualBranchDropsTheFormulaItDidNotComeFrom() {
        DailyRecord r = rate("84.7");
        r.setManualSealedHomeRate(n("90"));
        String note = TemperatureCalculator.calcBrokenDim(r, pools(39, 48, 21)).getNote();

        assertTrue(note.contains("家数封板率 90.0% → 3 分（人工）"), note);
        assertTrue(note.contains("回封率 21÷(21+48)=30.4% → 0 分"), note);
        assertTrue(note.contains("炸板率(次数) 84.7% → -3 分"), note);
    }

    /**
     * 同一个数必须只有一个长相：84.7 是从表单进来的位数，84.70 是 DECIMAL(5,2) 读回来的位数，
     * 差一个 0 他会以为这条算式和那天那个数是两回事。
     */
    @Test
    void oneNumberHasOneSpellingWhicheverPathWroteIt() {
        String typed = TemperatureCalculator.calcBrokenDim(rate("84.7"), pools(39, 48, 21)).getNote();
        String loaded = TemperatureCalculator.calcBrokenDim(rate("84.70"), pools(39, 48, 21)).getNote();
        assertEquals(typed, loaded);
        assertTrue(loaded.contains("炸板率(次数) 84.7% → -3 分"), loaded);
    }

    /** 清空即回退：人工列一旦回到 NULL，串和分都必须和"从没改过"一字不差。 */
    @Test
    void clearingAManualCellRestoresTheAutoReadingByteForByte() {
        String autoNote = TemperatureCalculator.calcBrokenDim(rate("84.7"), pools(39, 48, 21)).getNote();

        DailyRecord r = rate("84.7");
        r.setManualSealedHomeRate(n("90"));
        r.setManualResealRate(n("12"));
        r.setManualSealedHomeRate(null);
        r.setManualResealRate(null);

        assertEquals(autoNote, TemperatureCalculator.calcBrokenDim(r, pools(39, 48, 21)).getNote());
    }

    // ---------- 第 2 维 ----------

    /** 没填人工值时，新的 (record, inputs) 入口必须和老的只读入口给出同一个数——这是"一个字都不动"的算法层版本。 */
    @Test
    void emptyRecordScoresThePremiumDimExactlyAsBefore() {
        String[][] cases = {{"2:5.00", "7:-5.00"}, {"2:2.20", "5:5.00"}, {"3:-1.00", "4:0.50", "6:9.90"}};
        for (String[] aCase : cases) {
            ScoreInputs in = ScoreInputs.empty();
            in.setPremiumTiers(tiers(aCase));
            assertEquals(TemperatureCalculator.calcPremiumScore(in.getPremiumTiers()),
                    TemperatureCalculator.calcPremiumScore(new DailyRecord(), in),
                    "自动路径的溢价分被人工那层改动了: " + java.util.Arrays.toString(aCase));
            assertEquals(0, TemperatureCalculator.compositePremiumPct(in.getPremiumTiers())
                    .compareTo(TemperatureCalculator.compositePremiumPct(new DailyRecord(), in)));
        }
    }

    /** 高位组手改成 +5%：权重 2.5 的一档换了数，合成分与合成溢价必须一起换。 */
    @Test
    void manualPremiumGroupFeedsBothTheScoreAndTheTraceValue() {
        ScoreInputs in = ScoreInputs.empty();
        in.setPremiumTiers(tiers("2:5.00", "7:-5.00"));

        DailyRecord auto = new DailyRecord();
        DailyRecord manual = new DailyRecord();
        manual.setManualPremiumHighPct(n("5.00"));

        // 自动：最高板 7 → M=round(7/2)=4，于是 2 板在低档、7 板在高档、中档那天没票（权重摊回）。
        // 低档 +5% 判 2 分（">5" 没过去、">3" 过去，权重 1），高档 -5% 判 -3（">= -5" 那一格，权重 2.5）
        // → (2 − 7.5)/3.5 = −1.57 → -2
        assertEquals(-2, TemperatureCalculator.calcPremiumScore(auto, in).intValue());
        // 手改高位组为 +5%：两档都是 2 分，合成仍是 2
        assertEquals(2, TemperatureCalculator.calcPremiumScore(manual, in).intValue());
        // 自动的合成溢价 = (1×5 + 2.5×−5)/3.5 = −2.14：负数，人工那版必须明显高于它
        assertTrue(n("5.00").compareTo(TemperatureCalculator.compositePremiumPct(auto, in)) > 0);
        assertEquals(0, n("5.00").compareTo(TemperatureCalculator.compositePremiumPct(manual, in)));
    }

    /**
     * 那天公开档位表整个没回补（{@code tiers == null}）也不再等于这一维必缺：
     * 他手上有 03 篇要的那个均涨幅，就该能让它进分。三组全空仍是未评。
     */
    @Test
    void manualPremiumGroupsScoreWithoutAnyTierRows() {
        ScoreInputs empty = ScoreInputs.empty();
        assertNull(TemperatureCalculator.calcPremiumScore(new DailyRecord(), empty));

        DailyRecord r = new DailyRecord();
        r.setManualPremiumLowPct(n("1.50"));
        // 重定标后 +1.5% 落在 ">1" 那一档 = 0 分：想拿 2 分得 >3%，>5% 才是 3 分。
        assertEquals(0, TemperatureCalculator.calcPremiumScore(r, empty).intValue());
        assertEquals(0, n("1.50").compareTo(TemperatureCalculator.compositePremiumPct(r, empty)));
        assertNotNull(TemperatureCalculator.calcPremiumScore(r, null));
    }

    /** 第 9 维进不进分只有一处判据，界面上那句"为什么没进分"复用它，不另写一遍。 */
    @Test
    void survivalNeedsBothCountAndPremium() {
        assertTrue(TemperatureCalculator.survivalScored(3, n("-1.20")));
        assertFalse(TemperatureCalculator.survivalScored(null, n("-1.20")));
        assertFalse(TemperatureCalculator.survivalScored(0, n("-1.20")));
        assertFalse(TemperatureCalculator.survivalScored(3, null));
    }

    /** 手改的组均涨幅不该被"当天最高板是多少"牵着走——人工值本来就是按组给的。 */
    @Test
    void manualPremiumAppliesToTheNamedGroupNotToATier() {
        ScoreInputs in = ScoreInputs.empty();
        in.setPremiumTiers(tiers("2:1.00", "4:1.00"));
        DailyRecord r = new DailyRecord();
        r.setManualPremiumMidPct(n("-6.00"));

        // 自动：最高板 4 → M=2，于是 2 板在中档、4 板在高档、低档没票。+1.00 没越过 ">1" 那条线，
        // 落到 ">=0" 档判 -1 分（权重 1.5 + 2.5），两档同分 → 合成仍是 -1
        assertEquals(-1, TemperatureCalculator.calcPremiumScore(new DailyRecord(), in).intValue());
        // 中位手改成 -6%：判 -3 分，(1.5×−3 + 2.5×−1)/4 = −1.75 → -2
        assertEquals(-2, TemperatureCalculator.calcPremiumScore(r, in).intValue());
    }

    private static MarketMetrics.PremiumTiers tiers(String... boardAndPct) {
        java.util.List<com.emotion.market.PoolRow> rows = new java.util.ArrayList<>();
        java.util.Map<String, BigDecimal> pct = new java.util.HashMap<>();
        for (int i = 0; i < boardAndPct.length; i++) {
            String[] parts = boardAndPct[i].split(":");
            String code = String.format("60%04d", i);
            com.emotion.market.PoolRow row = new com.emotion.market.PoolRow();
            row.setCode(code);
            row.setMarket(1);
            row.setLbc(Integer.parseInt(parts[0]));
            rows.add(row);
            pct.put(code, parts.length > 1 && !parts[1].isEmpty() ? n(parts[1]) : null);
        }
        return MarketMetrics.premiumTiers(rows, pct);
    }
}
