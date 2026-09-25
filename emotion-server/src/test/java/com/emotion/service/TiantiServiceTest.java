package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.vo.TiantiVO;

/**
 * 一字断魂刀打标口径（{@link TiantiService#isDuanDao}）与封板形态分档（{@link TiantiService#sealForm}）。
 * 全内存 fixture，不碰 DB / Spring。
 *
 * <p>2026-09-17 修正后口径：今日+昨日连续两天锁死(首封≤93030 & 0炸板)、板数≥2、
 * 流通市值≤35亿、封单≥10亿 或 封成比(封单/成交额)≥3、换手率&lt;5%，五者同时成立才打标。
 * 不再要求"恰 3 板 + 三根全一字"（直线拉升=分时斜率，启动板可能是秒板而非一字）
 * 也不再硬卡"流通≤20亿"（瑞尔特 22~24 亿属典型小盘断魂刀，放宽到 35 亿）。
 */
class TiantiServiceTest {

    private static final BigDecimal MV_OK = new BigDecimal("2400000000");   // 24 亿(瑞尔特量级) ≤35 亿
    private static final BigDecimal MV_OVER = new BigDecimal("3600000000"); // 36 亿 >35 亿
    private static final BigDecimal SEAL_BIG = new BigDecimal("1200000000"); // 12 亿 ≥10 亿
    private static final BigDecimal AMT_TINY = new BigDecimal("100000000");  // 1 亿
    private static final BigDecimal TURN_LOW = new BigDecimal("2");          // 2% <5%
    private static final BigDecimal TURN_HIGH = new BigDecimal("6");         // 6% ≥5%

    /** 今日候选：秒板(92500)一封到底、流通≤35亿、封单充足、换手<5%。 */
    private static MarketStock hit() {
        MarketStock s = new MarketStock();
        s.setCode("HIT");
        s.setConsecutive(3);
        s.setPool(MarketStock.POOL_LIMIT_UP);
        s.setFirstSealTime(92500);
        s.setBreakCount(0);
        s.setFloatMv(MV_OK);
        s.setSealAmount(SEAL_BIG);
        s.setAmount(AMT_TINY);
        s.setTurnoverRate(TURN_LOW);
        return s;
    }

    /** 昨日/前日同代码的一封到死种子（秒板 0 炸板）。 */
    private static MarketStock prevLocked() {
        MarketStock s = new MarketStock();
        s.setCode("HIT");
        s.setConsecutive(2);
        s.setPool(MarketStock.POOL_LIMIT_UP);
        s.setFirstSealTime(92500);
        s.setBreakCount(0);
        return s;
    }

    private static List<MarketStock> prev(MarketStock... rows) {
        return rows.length == 0 ? Collections.emptyList() : java.util.Arrays.asList(rows);
    }

    // ---------------- 命中最优路径 ----------------

    @Test
    void fullMatch_allConditions_true() {
        assertTrue(TiantiService.isDuanDao(hit(), prev(prevLocked())));
    }

    @Test
    void twoBoardsAlsoMatch_notRigidOnThree() {
        // "恰 3 板"已放宽：2 连板、今日秒板启动即链路成立（启动板可能就是今天的秒板）
        MarketStock s = hit();
        s.setConsecutive(2);
        assertTrue(TiantiService.isDuanDao(s, prev(prevLocked())));
    }

    // ---------------- 今日锁死（≤93030 & 0 炸板） ----------------

    @Test
    void todayNotLocked_false() {
        // 早盘直线 93500 > 93030：今日不算"锁死不给上车"
        MarketStock line = hit();
        line.setFirstSealTime(93500);
        assertFalse(TiantiService.isDuanDao(line, prev(prevLocked())));
        // 一字 92500 但盘中开过板（T字）：非一封到底
        MarketStock tShape = hit();
        tShape.setBreakCount(1);
        assertFalse(TiantiService.isDuanDao(tShape, prev(prevLocked())));
        // 没首封时间：判不了
        MarketStock noFbt = hit();
        noFbt.setFirstSealTime(null);
        assertFalse(TiantiService.isDuanDao(noFbt, prev(prevLocked())));
    }

    @Test
    void belowTwoBoards_false() {
        MarketStock first = hit();
        first.setConsecutive(1);
        assertFalse(TiantiService.isDuanDao(first, prev(prevLocked())));
    }

    // ---------------- 昨日连续锁死 ----------------

    @Test
    void prevMissingOrNotLocked_false() {
        // 昨日池没有该 code
        assertFalse(TiantiService.isDuanDao(hit(), Collections.emptyList()));
        // 昨日该 code 但非锁死（今日 93500 早盘直线）
        MarketStock prevLine = prevLocked();
        prevLine.setFirstSealTime(93500);
        assertFalse(TiantiService.isDuanDao(hit(), prev(prevLine)));
    }

    // ---------------- 流通市值 / 封单 / 换手 ----------------

    @Test
    void floatMvOutOfCap_false() {
        MarketStock over = hit();
        over.setFloatMv(MV_OVER);
        assertFalse(TiantiService.isDuanDao(over, prev(prevLocked())));
        MarketStock nullMv = hit();
        nullMv.setFloatMv(null);
        assertFalse(TiantiService.isDuanDao(nullMv, prev(prevLocked())));
    }

    @Test
    void sealHuge_orRatioAtLeastThree() {
        // 封单 <10亿 但封成比 ≥3 也成立：瑞尔特 9/11 的比值 3.88 即命中
        MarketStock ruierte = hit();
        ruierte.setSealAmount(new BigDecimal("385000000")); // 3.85 亿 <10亿
        ruierte.setAmount(new BigDecimal("99000000"));      // 成交 0.99亿 → 封成比 3.89 ≥3
        ruierte.setFloatMv(new BigDecimal("2430000000"));   // 24.3 亿 ≤35
        ruierte.setTurnoverRate(new BigDecimal("4.09"));
        assertTrue(TiantiService.isDuanDao(ruierte, prev(prevLocked())));
        // 封成比 2 <3 应 false
        MarketStock ratioLow = hit();
        ratioLow.setSealAmount(new BigDecimal("200000000"));
        ratioLow.setAmount(AMT_TINY);
        assertFalse(TiantiService.isDuanDao(ratioLow, prev(prevLocked())));
        // 封单 null → false
        MarketStock nullSeal = hit();
        nullSeal.setSealAmount(null);
        assertFalse(TiantiService.isDuanDao(nullSeal, prev(prevLocked())));
    }

    @Test
    void turnoverBelowFive_required() {
        MarketStock low = hit();
        low.setTurnoverRate(TURN_HIGH);
        assertFalse(TiantiService.isDuanDao(low, prev(prevLocked())));
        MarketStock nullTurn = hit();
        nullTurn.setTurnoverRate(null);
        assertFalse(TiantiService.isDuanDao(nullTurn, prev(prevLocked())));
    }

    @Test
    void nullRow_false() {
        assertFalse(TiantiService.isDuanDao(null, prev(prevLocked())));
    }

    // ---------------- 封板形态分档 ----------------

    @Test
    void sealForm_bucketsByFirstSealTime_andMarksReopen() {
        assertEquals("一字", TiantiService.sealForm(92500, 0));
        assertEquals("早盘秒板", TiantiService.sealForm(93030, 0));
        assertEquals("早盘直线", TiantiService.sealForm(93031, 0));
        assertEquals("早盘直线", TiantiService.sealForm(93500, 0));
        assertEquals("早盘板", TiantiService.sealForm(93501, 0));
        assertEquals("上午板", TiantiService.sealForm(113000, 0));
        assertEquals("午后板", TiantiService.sealForm(140000, 0));
        assertEquals("尾盘板", TiantiService.sealForm(143001, 0));
        assertEquals("一字(回头2)", TiantiService.sealForm(92000, 2));
        assertNull(TiantiService.sealForm(null, 0));
    }

    // ---------------- duanDaoCodes：候选池那侧的问法 ----------------

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
    private static final LocalDate PREV = LocalDate.of(2026, 9, 23);

    /** 今日池里的一只票：判据要看的就是首封时间与炸板次数。 */
    private static MarketStock todayRow(String code, int firstSeal, int breakCount) {
        MarketStock s = hit();
        s.setCode(code);
        s.setFirstSealTime(firstSeal);
        s.setBreakCount(breakCount);
        return s;
    }

    /** 昨日池里的一只「锁死」票——isPrevLocked 只看代码与锁死，其余字段用不到。 */
    private static MarketStock prevRowLocked(String code) {
        MarketStock s = new MarketStock();
        s.setCode(code);
        s.setPool(MarketStock.POOL_LIMIT_UP);
        s.setFirstSealTime(92500);
        s.setBreakCount(0);
        return s;
    }

    /**
     * 造一个只连 mapper 的 service。
     *
     * <p>selectList 连调两次：第一次是当日池、第二次是昨日池——这正是 duanDaoCodes 的顺序，
     * 所以下面的 fixture 刻意做成不对称的（今日池 3 只、昨日池同码但锁死情况不同），
     * 万一以后有人把两次查询调换，用例会红而不是悄悄放过。
     */
    private static TiantiService serviceOn(List<MarketStock> todayPool, List<MarketStock> prevPool,
                                          boolean prevExists) {
        MarketStockMapper mapper = mock(MarketStockMapper.class);
        if (prevExists) {
            when(mapper.prevDetailDate(TODAY)).thenReturn(PREV);
            when(mapper.selectList(any())).thenReturn(todayPool, prevPool);
        } else {
            when(mapper.prevDetailDate(TODAY)).thenReturn(null);
            when(mapper.selectList(any())).thenReturn(todayPool);
        }
        return new TiantiService(null, mapper, null, null, null, null);
    }

    @Test
    void duanDaoCodes_onlyReturnsAskedAndLockedCodes() {
        // A 今日锁死 → 命中；B 今日 10:00 才封（不算锁死）→ 不命中；C 锁死但没问它 → 不该出现
        List<MarketStock> today = java.util.Arrays.asList(todayRow("A", 92500, 0),
                todayRow("B", 100000, 0), todayRow("C", 92500, 0));
        List<MarketStock> prev = java.util.Arrays.asList(prevRowLocked("A"),
                prevRowLocked("B"), prevRowLocked("C"));

        Set<String> hit = serviceOn(today, prev, true)
                .duanDaoCodes(TODAY, java.util.Arrays.asList("A", "B"));

        assertEquals(new HashSet<>(java.util.Arrays.asList("A")), hit);
    }

    @Test
    void duanDaoCodes_prevPoolMissing_returnsEmpty() {
        // 取不到昨日池（库里最早那天）时判不了「连续锁死」——宁可漏标，也不要凭空标一个假信号
        List<MarketStock> today = java.util.Arrays.asList(todayRow("A", 92500, 0));
        assertTrue(serviceOn(today, Collections.<MarketStock>emptyList(), true)
                .duanDaoCodes(TODAY, java.util.Arrays.asList("A")).isEmpty());
    }

    @Test
    void duanDaoCodes_emptyInput_doesNotTouchDb() {
        MarketStockMapper mapper = mock(MarketStockMapper.class);
        TiantiService svc = new TiantiService(null, mapper, null, null, null, null);

        assertTrue(svc.duanDaoCodes(TODAY, Collections.<String>emptyList()).isEmpty());
        assertTrue(svc.duanDaoCodes(null, java.util.Arrays.asList("A")).isEmpty());

        verify(mapper, never()).selectList(any());
        verify(mapper, never()).prevDetailDate(any());
    }

    // ---------------- 动态混沌破壁（detectBreaks） ----------------

    private static TiantiVO.HeightPoint pt(String date, int h, String... codes) {
        TiantiVO.HeightPoint p = new TiantiVO.HeightPoint();
        p.setTradeDate(LocalDate.parse(date));
        p.setMaxHeight(h);
        List<TiantiVO.HeightStock> stocks = new java.util.ArrayList<>();
        for (String c : codes) {
            stocks.add(new TiantiVO.HeightStock(c, c + "科技"));
        }
        p.setStocks(stocks);
        return p;
    }

    /** (日期, 代码) → 启动日：只填每天最高板名单上用得到的组合。 */
    private static Map<LocalDate, Map<String, LocalDate>> starts(Object... triples) {
        Map<LocalDate, Map<String, LocalDate>> out = new HashMap<>();
        for (int i = 0; i < triples.length; i += 3) {
            LocalDate date = LocalDate.parse((String) triples[i]);
            String code = (String) triples[i + 1];
            LocalDate start = LocalDate.parse((String) triples[i + 2]);
            out.computeIfAbsent(date, k -> new HashMap<>()).put(code, start);
        }
        return out;
    }

    @Test
    void breaks_lineFollowsChaosHigh_notWeldedToFiveBoard() {
        // 混沌里最高只到 4 → 新龙 5 板即破壁；混沌里出过 6 板活口 → 必须 7 才破
        List<TiantiVO.HeightPoint> weak = java.util.Arrays.asList(
                pt("2026-09-01", 4, "OLD"),
                pt("2026-09-02", 5, "NEW1"));
        TiantiService.detectBreaks(weak, starts("2026-09-01", "OLD", "2026-08-20",
                "2026-09-02", "NEW1", "2026-09-02"));
        assertTrue(weak.get(1).getIsBreak() != null && weak.get(1).getIsBreak());
        assertEquals(Integer.valueOf(4), weak.get(1).getPrevHigh());

        List<TiantiVO.HeightPoint> hot = java.util.Arrays.asList(
                pt("2026-09-01", 4, "OLD"),
                pt("2026-09-02", 6, "NEW1"),
                pt("2026-09-03", 6, "NEW2"),
                pt("2026-09-04", 7, "NEW3"));
        TiantiService.detectBreaks(hot, starts("2026-09-01", "OLD", "2026-08-20",
                "2026-09-02", "NEW1", "2026-09-02",
                "2026-09-03", "NEW2", "2026-09-03",
                "2026-09-04", "NEW3", "2026-09-04"));
        // NEW1 6 板破 4→... 达 minAbs(4)=5 → 先破一次并把线抬到周期态
        assertEquals(Integer.valueOf(6), hot.get(2).getCeiling());
        assertNull(hot.get(2).getIsBreak());
        // NEW3 只有超过 6 才算了结，7 板成立
        assertEquals(Integer.valueOf(6), hot.get(3).getPrevHigh());
    }

    @Test
    void breaks_companionReboundNeitherBreaksNorRaisesCeiling() {
        // 复刻 08-31→09-01：旧龙断板后，上一周期就在场的伴生票接棒创新高
        List<TiantiVO.HeightPoint> pts = java.util.Arrays.asList(
                pt("2026-08-29", 6, "TAIL"),
                pt("2026-08-30", 7, "TAIL"),
                pt("2026-08-31", 4, "FRESH0"),
                pt("2026-09-01", 7, "FRESH1"));
        TiantiService.detectBreaks(pts, starts("2026-08-29", "TAIL", "2026-08-24",
                "2026-08-30", "TAIL", "2026-08-24",
                "2026-08-31", "FRESH0", "2026-08-31",
                "2026-09-01", "FRESH1", "2026-08-31"));

        assertNull(pts.get(1).getIsBreak(), "伴生票反包创新高不算破壁");
        assertEquals(Integer.valueOf(6), pts.get(1).getCeiling(), "伴生的 7 板不能变成新天花板");
        assertTrue(Boolean.TRUE.equals(pts.get(3).getIsBreak()), "新龙按混沌高+1 破壁");
        assertEquals(Integer.valueOf(6), pts.get(3).getPrevHigh());
    }

    @Test
    void breaks_sittingDragonAddingBoardsIsSameBreak() {
        List<TiantiVO.HeightPoint> pts = java.util.Arrays.asList(
                pt("2026-09-01", 4, "X"),
                pt("2026-09-02", 5, "D"),
                pt("2026-09-03", 6, "D"),
                pt("2026-09-04", 7, "D"),
                pt("2026-09-05", 5, "E"),
                pt("2026-09-08", 6, "E2"));
        TiantiService.detectBreaks(pts, starts("2026-09-01", "X", "2026-09-01",
                "2026-09-02", "D", "2026-09-02",
                "2026-09-03", "D", "2026-09-02",
                "2026-09-04", "D", "2026-09-02",
                "2026-09-05", "E", "2026-09-01",
                "2026-09-08", "E2", "2026-09-05"));

        assertTrue(Boolean.TRUE.equals(pts.get(1).getIsBreak()));
        assertNull(pts.get(2).getIsBreak(), "5→6 是同一次破壁的延续");
        assertNull(pts.get(3).getIsBreak(), "5→6→7 仍然只算一次");
        assertEquals(Integer.valueOf(7), pts.get(3).getCeiling());
        assertTrue(Boolean.TRUE.equals(pts.get(5).getIsBreak()), "在位龙掉出名单后重新开混沌，新龙再破");
    }

    @Test
    void breaks_minAbsFloorBlocksIceAgeThreeBoard() {
        List<TiantiVO.HeightPoint> pts = java.util.Arrays.asList(
                pt("2026-09-01", 2, "X"),
                pt("2026-09-02", 3, "A"),
                pt("2026-09-03", 4, "A"),
                pt("2026-09-04", 5, "B"));
        TiantiService.detectBreaks(pts, starts("2026-09-01", "X", "2026-09-01",
                "2026-09-02", "A", "2026-09-02",
                "2026-09-03", "A", "2026-09-02",
                "2026-09-04", "B", "2026-09-04"));

        // 混沌高跟着市场实际最高板走，兜底线取的是「抬高之后」那一档：
        // 线 2→3 要 5 板、3→4 要 5 板，所以冰点期 3、4 板都不算破壁
        assertNull(pts.get(1).getIsBreak(), "3 板未达 minAbs(2)=4，只把线抬到 3");
        assertEquals(Integer.valueOf(3), pts.get(1).getCeiling());
        assertNull(pts.get(2).getIsBreak(), "4 板未达 minAbs(3)=5");
        assertEquals(Integer.valueOf(4), pts.get(2).getCeiling());
        assertTrue(Boolean.TRUE.equals(pts.get(3).getIsBreak()), "5 板才破壁");
        assertEquals(Integer.valueOf(4), pts.get(3).getPrevHigh());
        assertEquals(Integer.valueOf(4), TiantiService.minAbsBreak(2));
        assertEquals(Integer.valueOf(5), TiantiService.minAbsBreak(4));
        assertEquals(Integer.valueOf(6), TiantiService.minAbsBreak(5));
    }

    @Test
    void leader_companionOutclimbsSittingDragonEstablishesCycle() {
        // 复刻 08-25 汉森首次破壁 → 08-27 深中华A 越过汉森高度确立自己的周期
        List<TiantiVO.HeightPoint> pts = java.util.Arrays.asList(
                pt("2026-08-18", 4, "OLD1"),
                pt("2026-08-19", 5, "HAN"),
                pt("2026-08-20", 6, "SHEN"),
                pt("2026-08-21", 7, "SHEN"),
                pt("2026-08-24", 7, "GULL"));
        TiantiService.detectBreaks(pts, starts("2026-08-18", "OLD1", "2026-08-15",
                "2026-08-19", "HAN", "2026-08-19",
                "2026-08-20", "SHEN", "2026-08-16",
                "2026-08-21", "SHEN", "2026-08-16",
                "2026-08-24", "GULL", "2026-08-17"));

        assertTrue(Boolean.TRUE.equals(pts.get(1).getIsBreak()), "新龙 5 板破混沌 4 板线");
        assertTrue(Boolean.TRUE.equals(pts.get(1).getIsLeader()), "破壁当天同时开周期");
        assertEquals("HAN", pts.get(1).getLeaderStock().getCode());

        assertNull(pts.get(2).getIsBreak(), "伴生票超高度不算破壁");
        assertTrue(Boolean.TRUE.equals(pts.get(2).getIsLeader()), "越过在册龙头高度即换龙");
        assertEquals("SHEN", pts.get(2).getLeaderStock().getCode());

        assertNull(pts.get(3).getIsLeader(), "在册龙头自己加板是同一次周期延续");
        assertEquals(Integer.valueOf(7), pts.get(3).getCycleTop());
        assertEquals("SHEN科技", pts.get(3).getCycleLeader());

        assertNull(pts.get(4).getIsLeader(), "只到平高度算反包不算换龙");
        assertNull(pts.get(4).getIsBreak());
    }
}