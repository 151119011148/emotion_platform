package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * 覆盖：四层划界(H=2/4/5/7/8/9/10/12) 边界、晋级率日环比、溢价按档位家数加权、大面用昨日 ZT∩今日炸板 code 归属、
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
        return LadderMetricsService.aggregate(todayZT, todayLoss, prevZT, pools, tiers, idx, record, recent);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertTrue(actual != null, "value was null, expected " + expected);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but got " + actual);
    }

    // ---------------- 四层划界：H 动态 ----------------

    @Test
    void hsplit_isMaxOfFourAndCeilingHalfH() {
        assertEquals(4, LadderMetricsService.hsplit(2));
        assertEquals(4, LadderMetricsService.hsplit(4));
        assertEquals(4, LadderMetricsService.hsplit(5));
        assertEquals(4, LadderMetricsService.hsplit(7));
        assertEquals(4, LadderMetricsService.hsplit(8));
        assertEquals(5, LadderMetricsService.hsplit(9));
        assertEquals(5, LadderMetricsService.hsplit(10));
        assertEquals(6, LadderMetricsService.hsplit(12));
    }

    @Test
    void layerIndex_lowIsTwoMidIsThreeFourAndHighBandsSplitByH() {
        // 低位恒=2；中位恒=3~4；5 板归中高还是极高，取决于 H。
        for (int h : new int[] { 5, 6, 7, 8 }) {
            assertEquals(0, LadderMetricsService.layerIndex(2, h), "low @H=" + h);
            assertEquals(1, LadderMetricsService.layerIndex(3, h), "mid @H=" + h);
            assertEquals(1, LadderMetricsService.layerIndex(4, h), "mid @H=" + h);
            // H<9 时中高位段空档，5 板直接归极高（spec 的边界自然处理）。
            assertEquals(3, LadderMetricsService.layerIndex(5, h), "no midhigh @H=" + h);
        }
        assertEquals(2, LadderMetricsService.layerIndex(5, 9));
        assertEquals(3, LadderMetricsService.layerIndex(6, 9));
        assertEquals(2, LadderMetricsService.layerIndex(5, 10));
        assertEquals(3, LadderMetricsService.layerIndex(6, 10));
        assertEquals(3, LadderMetricsService.layerIndex(7, 10));
        assertEquals(2, LadderMetricsService.layerIndex(6, 12));
        assertEquals(3, LadderMetricsService.layerIndex(7, 12));
    }

    // ---------------- 四层晋级率：今 b 板 / 昨 b-1 板 ----------------

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
        // 低位 H=5 → 只有 5 板归极高，2/3/4 板都留在低/中，5 是 b<=hsplit(5)=4? 否 → 极高。
        // low: 今 2 板 3 家 / 昨 1 板 6 家
        assertAmount("50.00", m.get("jr_low"));
        // mid: 今 (3 板 2 + 4 板 0) = 2 / 昨 (2 板 2 + 3 板 0) = 2
        assertAmount("100.00", m.get("jr_mid"));
        // midhigh @H=5: hsplit=4 → 无 b<=4 且 b>=5 的档位落入 midhigh
        assertNull(m.get("jr_midhigh"), "midhigh 层当日无成员应缺席而非 0");
        // top: 今 5 板 1 家 / 昨 4 板 1 家
        assertAmount("100.00", m.get("jr_top"));
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

    // ---------------- 四层溢价：按档位家数加权 ----------------

    @Test
    void premLayer_weightedByStockCount() {
        // H=4 → 3、4 板都归中位；两档家数 10/5，均值 2/-1 → 加权 (20-5)/15 = 1.00
        List<MarketMetrics.TierPremium> tiers = Arrays.asList(
                tier(3, 10, "2.00"),
                tier(4, 5, "-1.00"),
                tier(2, 20, "5.00"));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, tiers, Collections.emptyList(), record(4, null, null, null, null, null),
                Collections.emptyList());
        assertAmount("1.00", m.get("prem_mid"));
        assertAmount("5.00", m.get("prem_low"));
        assertNull(m.get("prem_midhigh"));
        assertNull(m.get("prem_top"));
    }

    @Test
    void premLayer_nullAvgPct_excludedFromWeightedMean() {
        List<MarketMetrics.TierPremium> tiers = Arrays.asList(
                tier(3, 10, "2.00"),
                tier(4, 100, null));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, tiers, Collections.emptyList(), record(4, null, null, null, null, null),
                Collections.emptyList());
        // 未评的 4 板不参与加权，避免把"没取到价"稀释成"今天溢价很低"。
        assertAmount("2.00", m.get("prem_mid"));
    }

    // ---------------- 四层大面：昨 ZT 归属 ∩ 今 ZB+big_loss ----------------

    @Test
    void bigLayer_attributedByYesterdayBoard() {
        List<MarketStock> prev = Arrays.asList(
                zt("L1", 3), zt("L2", 5), zt("L3", 3), zt("L4", 2), zt("L5", 8));
        List<MarketStock> todayLoss = Arrays.asList(loss("L1"), loss("L2"), loss("L4"), loss("L5"), loss("ZZZ"));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), todayLoss, prev, null,
                Collections.emptyList(), Collections.emptyList(), record(8, null, null, null, null, null),
                Collections.emptyList());
        // H=8 → hsplit=4；board 2=low, 3=mid, 5=top, 8=top；ZZZ 非昨涨停池 → 不归属任何层。
        assertAmount("1", m.get("big_low"));
        assertAmount("1", m.get("big_mid"));
        assertAmount("0", m.get("big_midhigh"));
        assertAmount("2", m.get("big_top"));
    }

    @Test
    void firstToTwoBig_countsYesterdayFirstBoardThatBrokeToday() {
        List<MarketStock> prev = Arrays.asList(zt("F1", 1), zt("F2", 1), zt("F3", 1), zt("M1", 3));
        List<MarketStock> todayLoss = Arrays.asList(loss("F1"), loss("M1"));
        Map<String, BigDecimal> m = agg(Collections.emptyList(), todayLoss, prev, null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertAmount("1", m.get("first_1to2_big_count"));
    }

    // ---------------- 首板 / 1 进 2 ----------------

    @Test
    void firstCount_and_firstPromoRate() {
        List<MarketStock> today = Arrays.asList(zt("N1", 1), zt("N2", 1), zt("N3", 1), zt("N4", 1),
                zt("S1", 2), zt("S2", 2));
        List<MarketStock> prev = Arrays.asList(zt("P1", 1), zt("P2", 1), zt("P3", 1), zt("P4", 1));
        Map<String, BigDecimal> m = agg(today, Collections.emptyList(), prev, null,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertAmount("4", m.get("first_count"));
        assertAmount("2", m.get("board_total_count"));
        assertAmount("50.00", m.get("first_promo_1to2_rate"));
    }

    // ---------------- 家数封板率 / 回封率 ----------------

    @Test
    void sealedHome_and_reseal_fromPoolCounts() {
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                pools(80, 20, 10), Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertAmount("80.00", m.get("sealed_home_rate"));
        assertAmount("50.00", m.get("reseal_rate"));
    }

    @Test
    void poolCounts_zeroZb_resealAbsent() {
        Map<String, BigDecimal> m = agg(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                pools(50, 0, 0), Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList());
        assertAmount("100.00", m.get("sealed_home_rate"));
        assertFalse(m.containsKey("reseal_rate"), "zb=0 时分母为 0，应缺席而非 0.00");
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
        assertFalse(m.containsKey("jr_low"), "h<2 时四层不出");
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
