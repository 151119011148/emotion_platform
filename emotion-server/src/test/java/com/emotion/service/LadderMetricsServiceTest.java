package com.emotion.service;

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

import com.emotion.entity.DailyRecord;
import com.emotion.entity.IndexClose;
import com.emotion.entity.MarketDaily;
import com.emotion.entity.MarketStock;
import com.emotion.market.MarketMetrics;
import com.emotion.market.PoolCounts;

/**
 * 五维模型「自动取数聚合器」口径。全内存 fixture，不碰 DB / Spring。
 * 覆盖：三层划界(低=2/中=3-4/高=5板+，对齐高位生态) 边界、晋级率日环比、溢价按档位家数加权、大面用昨日 ZT∩今日炸板 code 归属、
 * 1 进 2、家数封板率/回封率、红盘率、量能 20 日均、指数 i→index{i}_pct 映射、缺读数=键缺席(绝不兜 0)。
 */
class LadderMetricsServiceTest {

    // ---------------- fixture 构造 ----------------

    private static MarketStock zt(String code, int board) {
        MarketStock s = new MarketStock();
        s.setCode(code);
        s.setConsecutive(board);
        s.setPool(MarketStock.POOL_LIMIT_UP);
        return s;
    }

    private static MarketStock loss(String code) {
        MarketStock s = new MarketStock();
        s.setCode(code);
        s.setPool(MarketStock.POOL_BROKEN);
        s.setBigLoss(1);
        return s;
    }

    private static MarketMetrics.TierPremium tier(int board, int stockCount, String avgPct) {
        BigDecimal avg = avgPct == null ? null : new BigDecimal(avgPct);
        int matched = avg == null ? 0 : Math.max(0, stockCount);
        return MarketMetrics.TierPremium.ofStored(board, stockCount, matched, avg, null, null);
    }

    private static IndexClose close(String code, String pct) {
        IndexClose c = new IndexClose();
        c.setIndexCode(code);
        c.setChangePct(new BigDecimal(pct));
        return c;
    }

    private static PoolCounts pools(int zt, int zb, int reseal) {
        PoolCounts p = new PoolCounts();
        p.setZtCount(zt);
        p.setZbCount(zb);
        p.setResealCount(reseal);
        return p;
    }

    private static DailyRecord record(Integer h, Integer lu, Integer ld, Integer up, Integer down, String totalVolume) {
        DailyRecord r = new DailyRecord();
        r.setMaxConsecutiveLimit(h);
        r.setLimitUpCount(lu);
        r.setLimitDownCount(ld);
        r.setUpCount(up);
        r.setDownCount(down);
        r.setTotalVolume(totalVolume == null ? null : new BigDecimal(totalVolume));
        return r;
    }

    /** 量能 20 日窗口的原料现在是全局客观日行 t_market_daily。 */
    private static MarketDaily mday(String totalVolume) {
        MarketDaily m = new MarketDaily();
        m.setTotalVolume(totalVolume == null ? null : new BigDecimal(totalVolume));
        return m;
    }

    private static Map<String, BigDecimal> agg(List<MarketStock> todayZT, List<MarketStock> todayLoss,
                                               List<MarketStock> prevZT, PoolCounts pools,
                                               List<MarketMetrics.TierPremium> tiers, List<IndexClose> idx,
                                               DailyRecord record, List<MarketDaily> recent) {
        return agg(todayZT, todayLoss, prevZT, pools, tiers, idx, record, recent,
                Collections.<String, BigDecimal>emptyMap());
    }

    private static Map<String, BigDecimal> agg(List<MarketStock> todayZT, List<MarketStock> todayLoss,
                                               List<MarketStock> prevZT, PoolCounts pools,
                                               List<MarketMetrics.TierPremium> tiers, List<IndexClose> idx,
                                               DailyRecord record, List<MarketDaily> recent,
                                               Map<String, BigDecimal> perfPct) {
        // 首板炸板默认空池：需要覆盖首板炸板率的用例走 aggWithBombs
        return LadderMetricsService.aggregate(todayZT, todayLoss, Collections.<MarketStock>emptyList(),
                prevZT, pools, tiers, idx, record, recent, perfPct);
    }

    /** 与 10 参 agg 相同，但显式传入今日全量炸板池（首板炸板率口径）。 */
    private static Map<String, BigDecimal> aggWithBombs(List<MarketStock> todayZT, List<MarketStock> todayLoss,
                                                         List<MarketStock> todayZB,
                                                         List<MarketStock> prevZT, PoolCounts pools,
                                                         List<MarketMetrics.TierPremium> tiers,
                                                         List<IndexClose> idx, DailyRecord record,
                                                         List<MarketDaily> recent, Map<String, BigDecimal> perfPct) {
        return LadderMetricsService.aggregate(todayZT, todayLoss, todayZB, prevZT, pools, tiers, idx, record,
                recent, perfPct);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertTrue(actual != null, "value was null, expected " + expected);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but got " + actual);
    }

    // ---------------- 三层划界：低=2 / 中=3-4 / 高=5板+ ----------------

    @Test
    void layerIndex_threeTiersFixed_alignedWithHighEcoD5() {
        // 三层固定划界（2026-09-13 简化，与高位生态 D5 同界）：低=2、中=3-4、高=5 板+，
        // 不再按 H 细分中高位/极高位（H<9 时中高位为空且与 D5 高度重复）。
        for (int h : new int[] { 2, 3, 4, 5, 7, 8, 9, 12 }) {
            assertEquals(0, LadderMetricsService.layerIndex(2, h), "low @H=" + h);
            assertEquals(1, LadderMetricsService.layerIndex(3, h), "mid @H=" + h);
            assertEquals(1, LadderMetricsService.layerIndex(4, h), "mid @H=" + h);
            assertEquals(2, LadderMetricsService.layerIndex(5, h), "high @H=" + h);
            assertEquals(2, LadderMetricsService.layerIndex(6, h), "high @H=" + h);
            assertEquals(2, LadderMetricsService.layerIndex(8, h), "high @H=" + h);
        }
    }

    // ---------------- 三层晋级率：今 b 板 / 昨 b-1 板 ----------------

    @Test
    void jrRates_matchDayOverDayBoardByBoard() {
        List<MarketStock> today = Arrays.asList(
                zt("A", 2), zt("B", 2), zt("C", 2),
                zt("D", 3), zt("E", 3),
                zt("F", 5));
        List<MarketStock> prev = Arrays.asList(
                zt("P1", 1), zt("P2", 1), zt("P3", 1), zt("P4", 1), zt("P5", 1), zt("P6", 1),
                zt("Q1", 2), zt("Q2", 2),
                zt("R1", 4));
        Map<String, BigDecimal> m = agg(today, Collections.emptyList(), prev, null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        // 三层：低位=今2板/昨1板；中位=今3-4板/昨2-3板；高位=今5板+/昨4+板。
        // low: 今 2 板 3 家 / 昨 1 板 6 家
        assertAmount("50.00", m.get("jr_low"));
        // mid: 今 (3 板 2 + 4 板 0) = 2 / 昨 (2 板 2 + 3 板 0) = 2
        assertAmount("100.00", m.get("jr_mid"));
        // high: 今 5 板 1 家 / 昨 4 板 1 家（5 板+对齐高位生态，不再细分）
        assertAmount("100.00", m.get("jr_high"));
        assertAmount("5", m.get("max_height"));
        assertAmount("6", m.get("board_total_count"));
        assertAmount("0", m.get("first_count"));
    }

    @Test
    void jrLayer_denominatorMissing_keyAbsentNotZero() {
        // 今日有 3 板但昨日无任何 2 板 → jr_mid 无分母，键必须缺席。
        List<MarketStock> today = Collections.singletonList(zt("X", 3));
        List<MarketStock> prev = Collections.singletonList(zt("Y", 5));
        Map<String, BigDecimal> m = agg(today, Collections.emptyList(), prev, null,
                Collections.emptyList(), Collections.emptyList(), record(3, null, null, null, null, null),
                Collections.emptyList());
        assertFalse(m.containsKey("jr_mid"), "无分母时不应写出 jr_mid");
        assertAmount("3", m.get("max_height"));
    }

    // ---------------- 三层溢价：按档位家数加权 ----------------

    @Test
    void premLayer_weightedByStockCount() {
        // 时间截面 PRD：tier.board=昨板高种子，归"今 board+1 板层"。H=4：
        // 低位溢价=昨首板全样本（从 prevZT 自关联 perf），tier board=1 已由全样本承接；
        // board=2/3→中位、board=4→今5板层，H=4 高位层不存在(N/A 丢弃)。
        List<MarketMetrics.TierPremium> tiers = Arrays.asList(
                tier(2, 20, "5.00"),  // 昨2板 → 今3板层 中位
                tier(3, 10, "2.00"),  // 昨3板 → 今4板层 中位
                tier(4, 5, "-1.00")); // 昨4板 → 今5板层，H=4 不存在 → 丢弃
        List<MarketStock> prevZT = Collections.singletonList(zt("F0", 1));
        Map<String, BigDecimal> perf = new HashMap<>();
        perf.put("F0", new BigDecimal("3.00"));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), prevZT, null,
                tiers, Collections.emptyList(), record(4, null, null, null, null, null),
                Collections.emptyList(), perf);
        assertAmount("3.00", m.get("prem_low"));
        assertAmount("4.00", m.get("prem_mid")); // (5*20 + 2*10)/30 = 4.00
        assertNull(m.get("prem_high"), "H=4 时高位层不存在，prem_high 应缺席=N/A");
    }

    @Test
    void premLayer_nullAvgPct_excludedFromWeightedMean() {
        // board=3 种子归今4板层=中位；board=4 无价不参与（H=5 时其高位层虽活跃，但无价排除）
        List<MarketMetrics.TierPremium> tiers = Arrays.asList(
                tier(3, 10, "2.00"),
                tier(4, 100, null));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, tiers, Collections.emptyList(), record(5, null, null, null, null, null),
                Collections.emptyList());
        // H=5：board=3→今4板中位；board=4 无价排除
        assertAmount("2.00", m.get("prem_mid"));
    }

    // ---------------- 三层大面：昨 ZT 归属 ∩ 今 ZB+big_loss ----------------

    @Test
    void bigLayer_attributedByYesterdaySeedBoard_plusOneLayer() {
        // 时间截面 PRD：层级以"今日板高"命名，昨 b 板种子的大面归 layerIndex(b+1)。
        // H=8：b=2/3 → 中位，b=5/8 → 高位(5板+，不再细分成极高)；ZZZ 非昨池不归属。
        List<MarketStock> prev = Arrays.asList(
                zt("L1", 3), zt("L2", 5), zt("L3", 3), zt("L4", 2), zt("L5", 8));
        List<MarketStock> todayLoss = Arrays.asList(loss("L1"), loss("L2"), loss("L4"), loss("L5"), loss("ZZZ"));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), todayLoss, prev, null,
                Collections.emptyList(), Collections.emptyList(), record(8, null, null, null, null, null),
                Collections.emptyList());
        assertAmount("0", m.get("big_low")); // 无昨首板大面
        assertAmount("2", m.get("big_mid")); // L1(昨3→今4层)、L4(昨2→今3层)
        assertAmount("2", m.get("big_high")); // L2(昨5→今6层)、L5(昨8高标断板→高位)
        assertAmount("66.67", m.get("big_mid_rate")); // 2/3
        assertAmount("100.00", m.get("big_high_rate")); // 2/2
    }

    @Test
    void bigLow_isFirstToTwoBigLoss_yesterdayFirstBoardSeeds() {
        // 低位大面 = 1进2大面：昨首板今 big_loss=1 计入；昨 3 板的 M1 归中位不进低位。
        // 今日需有 H≥2 三层才产出：用 record 携带 H=2。
        List<MarketStock> prev = Arrays.asList(zt("F1", 1), zt("F2", 1), zt("F3", 1), zt("M1", 3));
        List<MarketStock> todayLoss = Arrays.asList(loss("F1"), loss("M1"));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), todayLoss, prev, null,
                Collections.emptyList(), Collections.emptyList(), record(3, null, null, null, null, null),
                Collections.emptyList());
        assertAmount("1", m.get("big_low"));  // 仅 F1
        assertAmount("1", m.get("big_mid"));  // M1 昨3→今4层（H=3 时 layerIndex(4,3)=1）
    }

    @Test
    void bigLow_countsGoneStocksByClosePct_andExcludesPromoted() {
        // 昨首板 6 只：P1 炸板大面 / P2 未触板收 -8 / P3 未触板收 -7（边界严格，不计）
        // / P4 未触板收 -6.99 / P5 跌停收 -10（在 DT 不在 ZB）/ P6 晋级 2 板成功
        List<MarketStock> prev = Arrays.asList(
                zt("P1", 1), zt("P2", 1), zt("P3", 1), zt("P4", 1), zt("P5", 1), zt("P6", 1));
        List<MarketStock> today = Collections.singletonList(zt("P6", 2));
        List<MarketStock> todayLoss = Collections.singletonList(loss("P1"));
        Map<String, BigDecimal> perf = new HashMap<>();
        perf.put("P1", new BigDecimal("-1.90"));  // 炸板 big_loss=1，收盘虽浅仍计
        perf.put("P2", new BigDecimal("-8.00"));  // 未触板深水区
        perf.put("P3", new BigDecimal("-7.00"));  // =7% 不满足"回撤>7%"
        perf.put("P4", new BigDecimal("-6.99"));
        perf.put("P5", new BigDecimal("-10.00")); // 跌停，不在炸板池
        perf.put("P6", new BigDecimal("10.00"));  // 晋级成功，无论如何不数
        Map<String, BigDecimal> m = agg(today, todayLoss, prev, null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(), perf);
        // P1 + P2 + P5 = 3
        assertAmount("3", m.get("big_low"));
        // 1进2晋级率就是低位晋级 jr_low：今2板 1 / 昨1板 6
        assertAmount("16.67", m.get("jr_low"));
        assertAmount("6", m.get("jr_low_base"));
    }

    @Test
    void bigLow_withoutPerf_isBrokenPoolLowerBound_notFakeZero() {
        // perf 缺席（历史日未回补）：未触板票判不了，结果是炸板池下界；昨池存在且有 H 时仍出键。
        List<MarketStock> prev = Arrays.asList(zt("F1", 1), zt("F2", 1));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), prev, null,
                Collections.emptyList(), Collections.emptyList(), record(2, null, null, null, null, null),
                Collections.emptyList());
        assertAmount("0", m.get("big_low"));
    }

    // ---------------- 首板（T日）/ 1 进 2 已并入 D3 低位 ----------------

    @Test
    void firstCount_and_jrLow() {
        List<MarketStock> today = Arrays.asList(zt("N1", 1), zt("N2", 1), zt("N3", 1), zt("N4", 1),
                zt("S1", 2), zt("S2", 2));
        List<MarketStock> prev = Arrays.asList(zt("P1", 1), zt("P2", 1), zt("P3", 1), zt("P4", 1));
        Map<String, BigDecimal> m = agg(today, Collections.emptyList(), prev, null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertAmount("4", m.get("first_count"));
        assertAmount("2", m.get("board_total_count"));
        // 1进2晋级率时间截面重构后=低位晋级 jr_low（今2板2/昨1板4）
        assertAmount("50.00", m.get("jr_low"));
    }

    // ---------------- D4 首板生态·纯 T 日指标 ----------------

    private static MarketStock firstBoard(String code, String industry, Integer fbt, BigDecimal seal) {
        MarketStock s = zt(code, 1);
        s.setIndustry(industry);
        s.setFirstSealTime(fbt);
        s.setSealAmount(seal);
        return s;
    }

    private static MarketStock bomb(String code) {
        MarketStock s = new MarketStock();
        s.setCode(code);
        s.setPool(MarketStock.POOL_BROKEN);
        return s;
    }

    @Test
    void firstBoardDayMetrics_sealedAndBombRates_sealYiziTheme() {
        // 今日封住 4 只首板：3 只能判形态（2 只一字）、4 只均有封单；行业 元件×3/家居×1
        List<MarketStock> today = Arrays.asList(
                firstBoard("F1", "元件", 92500, new BigDecimal("100000000")),
                firstBoard("F2", "元件", 92500, new BigDecimal("60000000")),
                firstBoard("F3", "元件", 101500, new BigDecimal("20000000")),
                firstBoard("F4", "家居用品", null, new BigDecimal("20000000")));
        // 炸板池：B1 昨日已在涨停池=连板尝试不算首板炸板；B2 是首板炸板
        List<MarketStock> todayZB = Arrays.asList(bomb("B1"), bomb("B2"));
        List<MarketStock> prev = Collections.singletonList(zt("B1", 2));
        Map<String, BigDecimal> m = aggWithBombs(today, Collections.emptyList(), todayZB, prev, null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(),
                Collections.<String, BigDecimal>emptyMap());
        assertAmount("80.00", m.get("first_sealed_rate"));  // 4/(4+1)
        assertAmount("20.00", m.get("first_bomb_rate"));    // 1/5
        assertAmount("0.50", m.get("first_avg_seal_amount")); // (1e8+6e7+2e7+2e7)/4=5000万元=0.50亿
        assertAmount("66.67", m.get("first_yizi_ratio"));   // 2/3（无首封时间的 F4 不进分母）
        assertAmount("75.00", m.get("first_theme_gather_pct")); // 元件 3/4
    }

    @Test
    void firstBoardDayMetrics_withoutPrevPool_ratesAbsentNotFakeZero() {
        // 昨日明细缺失：无法判定首板炸板，两个率缺席；T 日封单/一字/聚集仍可算
        List<MarketStock> today = Collections.singletonList(
                firstBoard("F1", "元件", 100000, new BigDecimal("50000000")));
        Map<String, BigDecimal> m = aggWithBombs(today, Collections.emptyList(),
                Collections.singletonList(bomb("X")), Collections.<MarketStock>emptyList(), null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(),
                Collections.<String, BigDecimal>emptyMap());
        assertNull(m.get("first_sealed_rate"), "昨池缺失时首板封板率应缺席");
        assertNull(m.get("first_bomb_rate"));
        assertAmount("0.50", m.get("first_avg_seal_amount")); // 5000 万元 = 0.50 亿
    }

    // ---------------- 家数封板率 / 回封率 ----------------

    @Test
    void sealedHome_and_reseal_fromPoolCounts() {
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                pools(80, 20, 10), Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertAmount("80.00", m.get("sealed_home_rate"));
        // 回封率分母=回封+炸板（所有开过板的个股）：10/(10+20)=33.33，不是 10/20=50
        assertAmount("33.33", m.get("reseal_rate"));
    }

    @Test
    void poolCounts_zeroZb_resealAbsent() {
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                pools(50, 0, 0), Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertAmount("100.00", m.get("sealed_home_rate"));
        assertFalse(m.containsKey("reseal_rate"), "回封与炸板都为 0 时分母为 0，应缺席而非 0.00");
    }

    @Test
    void poolCounts_zeroZb_allResealed_isHundred() {
        // 回封多于炸板也不能算出 >100%：zb=0、5 只全是炸后回封 → 回封率 100%
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                pools(50, 0, 5), Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertAmount("100.00", m.get("reseal_rate"));
    }

    // ---------------- 大盘：涨跌停家数、红盘率、量能、指数 ----------------

    @Test
    void marketScalars_fromDailyRecord() {
        DailyRecord r = record(null, 90, 12, 600, 400, "1200");
        List<MarketDaily> recent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            recent.add(mday("1000"));
        }
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, Collections.emptyList(), Collections.emptyList(), r, recent);
        assertAmount("90", m.get("limit_up_count"));
        assertAmount("12", m.get("limit_down_count"));
        assertAmount("0.6000", m.get("red_ratio"));
        assertAmount("1.2000", m.get("turnover_ratio"));
    }

    @Test
    void turnover_windowTruncatesToTwentyAndIgnoresNulls() {
        DailyRecord r = record(null, null, null, null, null, "500");
        List<MarketDaily> recent = new ArrayList<>();
        // 45 条：最旧的 25 条不该进窗口。放 500 在前 25、100 在后 20，且穿插 null。
        for (int i = 0; i < 25; i++) {
            recent.add(mday("500"));
        }
        recent.add(mday(null));
        for (int i = 0; i < 20; i++) {
            recent.add(mday("100"));
        }
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, Collections.emptyList(), Collections.emptyList(), r, recent);
        assertAmount("5.0000", m.get("turnover_ratio"));
    }

    @Test
    void turnover_noHistory_keyAbsent() {
        DailyRecord r = record(null, null, null, null, null, "1200");
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, Collections.emptyList(), Collections.emptyList(), r, Collections.emptyList());
        assertFalse(m.containsKey("turnover_ratio"));
    }

    @Test
    void redRatio_upPlusDownZero_keyAbsent() {
        DailyRecord r = record(null, null, null, 0, 0, null);
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, Collections.emptyList(), Collections.emptyList(), r, Collections.emptyList());
        assertFalse(m.containsKey("red_ratio"));
    }

    @Test
    void indexCloses_mapByINDEX_CODESOrder() {
        List<IndexClose> idx = Arrays.asList(
                close("399001", "0.50"),
                close("000001", "1.20"),
                close("399006", "-0.80"),
                close("899050", "-2.00"));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, Collections.emptyList(), idx, null, Collections.emptyList());
        assertAmount("1.20", m.get("index1_pct"));
        assertAmount("0.50", m.get("index2_pct"));
        assertAmount("-0.80", m.get("index3_pct"));
        assertFalse(m.containsKey("index4_pct"), "只映射三指");
    }

    @Test
    void indexCloses_missingOneCode_onlyThatKeyAbsent() {
        List<IndexClose> idx = Arrays.asList(close("000001", "1.00"), close("399006", "0.60"));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, Collections.emptyList(), idx, null, Collections.emptyList());
        assertAmount("1.00", m.get("index1_pct"));
        assertFalse(m.containsKey("index2_pct"));
        assertAmount("0.60", m.get("index3_pct"));
    }

    // ---------------- 冰点 / 空输入：不抛，最小 map ----------------

    @Test
    void allEmpty_returnsMinimalMap_noThrow() {
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertTrue(m.isEmpty(), "fetcher 完全没跑 -> 所有键必须缺席而不是兜 0：" + m);
    }

    @Test
    void iceDay_onlyFirstBoards_layersSkippedMaxHeightStillRecorded() {
        List<MarketStock> today = Arrays.asList(zt("N1", 1), zt("N2", 1));
        Map<String, BigDecimal> m = agg(today, Collections.emptyList(), Collections.emptyList(), null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertAmount("2", m.get("first_count"));
        assertAmount("0", m.get("board_total_count"));
        assertAmount("1", m.get("max_height"));
        assertFalse(m.containsKey("jr_low"), "h<2 时三层不出");
    }

    @Test
    void maxHeight_fallsBackToRecord_whenDetailMissing() {
        DailyRecord r = record(7, null, null, null, null, null);
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, Collections.emptyList(), Collections.emptyList(), r, Collections.emptyList());
        assertAmount("7", m.get("max_height"));
    }

    @Test
    void constants_areIndexCodesAndTurnoverWindow() {
        assertEquals(3, LadderMetricsService.INDEX_CODES.length);
        assertEquals(20, LadderMetricsService.TURNOVER_WINDOW);
    }
}
