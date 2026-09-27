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
import java.util.HashSet;
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

    // ---------------- 破壁判定 v11（detectBreaks） ----------------
    // fixture 用真实曲线的日期与板高；codes[0] 即服务端名单首位（真实链路按代码升序，定线票取这一位）。
    // v11：旧龙断板那天起，线抬到它的高度 H 钉住 H−1 个交易日；钉满降到"这期间没追平线的那些天"的最高板，
    // 再按新线钉 新线−1 天——一级一级往下走，直到某只票追平并且次日续板。钉线期间照常判试探，
    // 同一条线上失败过的票只许换票；没有 TTL，也没有 v9 那种"沿用被破的线"的重试探窗。
    // 不带 lad() 的 fixture 走退化路径（没有连板名单，每只票的板高就取当天最高板，所以够线必有 ◆）。

    private static TiantiVO.HeightPoint pt(String date, int h, String... codes) {
        TiantiVO.HeightPoint p = new TiantiVO.HeightPoint();
        p.setTradeDate(LocalDate.parse(date));
        p.setMaxHeight(h);
        List<TiantiVO.HeightStock> stocks = new java.util.ArrayList<>();
        for (String c : codes) {
            stocks.add(new TiantiVO.HeightStock(c, c));
        }
        p.setStocks(stocks);
        return p;
    }

    /** 给某天补连板名单，形如 {@code "C=5", "W=7"}；名单上没有的票，判定按"已掉榜"处理。 */
    private static TiantiVO.HeightPoint lad(TiantiVO.HeightPoint p, String... specs) {
        List<TiantiVO.LadderStock> ladder = new java.util.ArrayList<>();
        for (String spec : specs) {
            int i = spec.indexOf('=');
            String code = spec.substring(0, i);
            ladder.add(new TiantiVO.LadderStock(Integer.valueOf(spec.substring(i + 1)), code, code));
        }
        p.setLadder(ladder);
        return p;
    }

    private static TiantiVO.HeightPoint on(List<TiantiVO.HeightPoint> pts, String date) {
        LocalDate d = LocalDate.parse(date);
        for (TiantiVO.HeightPoint p : pts) {
            if (d.equals(p.getTradeDate())) {
                return p;
            }
        }
        throw new AssertionError("fixture 缺 " + date);
    }

    private static void assertProbe(TiantiVO.HeightPoint p, String code) {
        assertProbe(p, code, "应记试探破壁");
    }

    private static void assertProbe(TiantiVO.HeightPoint p, String code, String why) {
        assertTrue(Boolean.TRUE.equals(p.getIsProbe()), p.getTradeDate() + " " + why);
        assertEquals(code, p.getProbeStock().getCode());
        assertNull(p.getIsBreak(), p.getTradeDate() + " 试探当天还不算破壁成功");
    }

    private static void assertBreak(TiantiVO.HeightPoint p, int prevHigh, String code) {
        assertTrue(Boolean.TRUE.equals(p.getIsBreak()), p.getTradeDate() + " 应记破壁成功");
        assertEquals(Integer.valueOf(prevHigh), p.getPrevHigh());
        assertEquals(code, p.getBreakStock().getCode());
    }

    /** 既没试探也没破壁：窗口内、没追平线、或追平的正是定线票本人。 */
    private static void assertQuiet(TiantiVO.HeightPoint p) {
        assertQuiet(p, "不该有标记");
    }

    private static void assertQuiet(TiantiVO.HeightPoint p, String why) {
        assertNull(p.getIsProbe(), p.getTradeDate() + " 不该有试探：" + why);
        assertNull(p.getIsBreak(), p.getTradeDate() + " 不该有破壁：" + why);
    }

    @Test
    void breaks_july_probeThenConfirmAndStaircaseDecay() {
        // 同一段日子，但取数首日就是 7.01（真实曲线从 4 月跑过来，相位不一样，见 breaks_realCurve…）。
        // 首日板高 3 → 没有旧龙可断，按"当天 3 板即 H"起一级，线钉在 3 板上 H−1=2 个交易日。
        // 7.02 定线票海南海药自己爬到 4 板 → 它就是在位龙、线跟它抬；7.03 它掉榜才算断板 →
        // 线抬回它的 4 板钉 4−1=3 个交易日，当天恒尚节能 4 板追平=试探，7.06 它 5 板=破壁成功（prev=4）。
        // 恒尚一路加板到 7.09 的 8 板，7.10 掉榜 → 线抬到 8 板钉 8−1=7 个交易日（7.10~7.20）：
        // 这七天市场最高只有 7.16 哈药股份的 5 板，没人够得着 8 板，所以整段没有 ◆（v10 的 7.15◆、7.17◆ 都没了）。
        // 7.21 钉满降到记录到的 5 板，定线票换成还站在 5 板上的哈药股份 → 7.22 立新能源追平、7.23 它 6 板破壁。
        List<TiantiVO.HeightPoint> pts = java.util.Arrays.asList(
                pt("2026-07-01", 3, "海南海药", "多氟多"),
                pt("2026-07-02", 4, "海南海药"),
                pt("2026-07-03", 4, "恒尚节能"),
                pt("2026-07-06", 5, "恒尚节能"),
                pt("2026-07-07", 6, "恒尚节能"),
                pt("2026-07-08", 7, "恒尚节能"),
                pt("2026-07-09", 8, "恒尚节能"),
                pt("2026-07-10", 2, "中鼎股份", "欢瑞世纪", "亚联机械"),
                pt("2026-07-13", 3, "亚联机械", "立方制药", "贵绳股份"),
                pt("2026-07-14", 3, "哈药股份"),
                pt("2026-07-15", 4, "哈药股份"),
                pt("2026-07-16", 5, "哈药股份"),
                pt("2026-07-17", 4, "艾艾精工"),
                pt("2026-07-20", 3, "立新能源"),
                pt("2026-07-21", 4, "立新能源"),
                pt("2026-07-22", 5, "立新能源"),
                pt("2026-07-23", 6, "立新能源"));
        TiantiService.detectBreaks(pts);

        assertEquals(Integer.valueOf(3), on(pts, "2026-07-01").getCeiling());
        assertProbe(on(pts, "2026-07-01"), "多氟多", "取数首日没有旧龙可断，当天板高就是第一级线");
        assertEquals(Integer.valueOf(4), on(pts, "2026-07-02").getCeiling(),
                "定线票海南海药自己爬到 4 板 → 线跟它抬，它就是在位龙");
        assertNull(on(pts, "2026-07-02").getIsChaos(), "龙还在榜，不在降线阶梯上");
        assertEquals("海南海药", on(pts, "2026-07-02").getLineStock().getCode());
        assertQuiet(on(pts, "2026-07-02"), "在位龙在册期间不判试探");
        assertEquals(Integer.valueOf(4), on(pts, "2026-07-03").getCeiling(), "海南海药 7.03 掉榜=断板，线钉回它的 4 板");
        assertEquals(Boolean.TRUE, on(pts, "2026-07-03").getIsChaos(), "断板当天就是这一级的第 1 天");
        assertProbe(on(pts, "2026-07-03"), "恒尚节能");
        assertBreak(on(pts, "2026-07-06"), 4, "恒尚节能");
        for (String d : new String[] { "2026-07-07", "2026-07-08", "2026-07-09" }) {
            assertQuiet(on(pts, d), "在位龙还在榜，加板只是同一次周期的延续");
        }
        assertEquals(Integer.valueOf(6), on(pts, "2026-07-07").getCeiling());
        assertEquals("恒尚节能", on(pts, "2026-07-07").getLineStock().getCode());
        assertEquals(Integer.valueOf(8), on(pts, "2026-07-09").getCeiling());

        assertQuiet(on(pts, "2026-07-10"), "恒尚断板，线抬到它的 8 板钉着");
        for (String d : new String[] { "2026-07-13", "2026-07-14", "2026-07-15", "2026-07-16",
                "2026-07-17", "2026-07-20" }) {
            assertEquals(Integer.valueOf(8), on(pts, d).getCeiling(), d + " 还在 8 板这一级，市场最高 5 板追不上");
            assertQuiet(on(pts, d), "够不着线就没有试探对象");
        }
        assertEquals(Integer.valueOf(5), on(pts, "2026-07-21").getCeiling(),
                "8−1=7 个交易日钉满，降到没追平的那些天里最高的 7.16 的 5 板");
        assertEquals("哈药股份", on(pts, "2026-07-21").getLineStock().getCode());
        assertProbe(on(pts, "2026-07-22"), "立新能源");
        assertBreak(on(pts, "2026-07-23"), 5, "立新能源");
        assertNull(on(pts, "2026-07-23").getIsProbe(), "在位龙继续加板不再另起一次试探");
        assertEquals(Integer.valueOf(6), on(pts, "2026-07-23").getCeiling());
    }

    @Test
    void breaks_september_pinnedLineTriesOtherStocksThenStepsDown() {
        // 取数首日 9.08 没有旧龙可断，按"当天 4 板即 H"起一级：线钉在 4 板上 4−1=3 个交易日。
        // 钉线期间照常判试探，只是同一条线上失败过的票不许再来：9.08 亚盛集团、9.09 百大集团（9.09 市场
        // 自己爬到 5 板，但那是"追平线的那一天"，不进降线记录）、9.10 桂林旅游、9.11 瑞尔特、9.14 闽东电力；
        // 这几天没人续板，也没有"低于线、又没人追平"的日子可记 → 钉满就原地再钉一级，线不许悬空。
        // 9.15 闽东电力 5 板追平、9.16 它 6 板=破壁成功。9.17 闽东断板：一律把线抬到它的 6 板重钉
        // 6−1=5 个交易日（9.17、9.18、9.21、9.22、9.23）——澳弘电子的 5 板和华瓷股份 9.18/9.21 的 4、5 板
        // 都追不上，所以 v10 的 ◆9.17、◆9.21 在这一版都不成立了；9.22 华瓷 6 板才追平，9.23 掉榜=破壁失败。
        // v13：华瓷追平过 6 板，这个高度也进降线记录 → 9.23 钉满，下一级还是 6，不降到 5；
        // 9.24 新华文轩 5 板够不着线，所以它不再算试探（换票重试的账因为没真降一级而留着）。
        List<TiantiVO.HeightPoint> pts = java.util.Arrays.asList(
                pt("2026-09-08", 4, "爱仕达", "亚盛集团", "百大集团"),
                pt("2026-09-09", 5, "百大集团"),
                pt("2026-09-10", 4, "桂林旅游"),
                pt("2026-09-11", 4, "瑞尔特"),
                pt("2026-09-14", 4, "闽东电力"),
                pt("2026-09-15", 5, "闽东电力"),
                pt("2026-09-16", 6, "闽东电力"),
                pt("2026-09-17", 5, "澳弘电子"),
                pt("2026-09-18", 4, "华瓷股份", "锡华科技"),
                pt("2026-09-21", 5, "华瓷股份"),
                pt("2026-09-22", 6, "华瓷股份"),
                pt("2026-09-23", 4, "大亚圣象", "新华文轩"),
                pt("2026-09-24", 5, "新华文轩"));
        TiantiService.detectBreaks(pts);

        assertProbe(on(pts, "2026-09-08"), "亚盛集团", "4 板这一级的第 1 天就有并列 4 板追平");
        assertEquals("爱仕达", on(pts, "2026-09-08").getLineStock().getCode());
        assertEquals(Integer.valueOf(4), on(pts, "2026-09-09").getCeiling(),
                "还钉在 4 板上：百大集团的 5 板算追平这一级，不算降线的记录");
        assertProbe(on(pts, "2026-09-09"), "百大集团");
        assertProbe(on(pts, "2026-09-10"), "桂林旅游", "百大没续板=破壁失败，同一条线上只许换票再试");
        assertProbe(on(pts, "2026-09-11"), "瑞尔特");
        assertEquals(Integer.valueOf(4), on(pts, "2026-09-11").getCeiling(),
                "这一级钉满了，可这几天全是追平或越过 4 板的日子，记不到更低的高度 → 原地再钉一级");
        assertProbe(on(pts, "2026-09-14"), "闽东电力");
        assertBreak(on(pts, "2026-09-15"), 4, "闽东电力");
        assertEquals(Integer.valueOf(6), on(pts, "2026-09-16").getCeiling(), "在位龙 6 板，线跟它抬");

        assertEquals(Integer.valueOf(6), on(pts, "2026-09-17").getCeiling(), "闽东 6 板断板 → 线抬到 6 板重钉");
        for (String d : new String[] { "2026-09-17", "2026-09-18", "2026-09-21" }) {
            assertQuiet(on(pts, d), "5 板追不平 6 板线，v10 在这里是给 ◆ 的");
        }
        assertProbe(on(pts, "2026-09-22"), "华瓷股份");
        assertQuiet(on(pts, "2026-09-23"), "华瓷没续板=破壁失败，线还钉在 6 板上");
        // v13：华瓷那次失败的 6 板也记进降线高度 → 9.23 钉满，下一级仍顶在 6，不降到 5。
        assertEquals(Integer.valueOf(6), on(pts, "2026-09-24").getCeiling(), "第 5 个交易日钉满，仍然原地钉 6 板");
        assertEquals("华瓷股份", on(pts, "2026-09-24").getLineStock().getCode(), "定线票留在记下这个高度的华瓷名下");
        assertQuiet(on(pts, "2026-09-24"), "新华文轩 5 板够不着 6 板线：他点名要撤掉的那个误标");
    }

    @Test
    void breaks_probeStockOwnBoardDecidesNotTheDaysHigh() {
        // 带连板名单的主路：首日 9.01 X 的 5 板起一级，线钉在 5 板上 5−1=4 个交易日（9.01~9.04）。
        // 钉线期间照常判试探，所以 9.04 C 追平 5 板是有 ◆ 的（v10 那句"只算记录"已经作废）。
        // 9.05 钉满降到没追平线的那些天（9.02、9.03 的 3 板）里最高的 3 板：市场最高跳到 W 的 7 板，
        // 试探记在 W 头上（同一天 C 也够线，并列取板高最高的）。
        // 9.06 比的是试探股自己的板高——W 自己还是 7 板没续上，这次算失败；同一级上刚判失败的票不再重复记试探，
        // 而它正是定线票、还在榜再创新高 → 它接棒成龙头，线跟它抬到 7（它自己爬的这一级不算它破的壁）。
        List<TiantiVO.HeightPoint> pts = java.util.Arrays.asList(
                lad(pt("2026-09-01", 5, "X"), "X=5"),
                lad(pt("2026-09-02", 3, "A"), "A=3"),
                lad(pt("2026-09-03", 3, "B"), "B=3"),
                lad(pt("2026-09-04", 5, "C"), "C=5"),
                lad(pt("2026-09-05", 7, "W"), "W=7", "C=5"),
                lad(pt("2026-09-06", 7, "W"), "W=7"),
                lad(pt("2026-09-07", 8, "W"), "W=8"));
        TiantiService.detectBreaks(pts);

        assertProbe(on(pts, "2026-09-04"), "C", "钉线的第 4 个交易日上 C 追平 5 板，照记试探");
        assertEquals(Integer.valueOf(5), on(pts, "2026-09-04").getCeiling());
        assertEquals(Integer.valueOf(3), on(pts, "2026-09-05").getCeiling(),
                "钉满降到没追平的那些天里最高的 3 板；C 的 5 板是追平，不进记录");
        assertNull(on(pts, "2026-09-05").getIsBreak(), "C 自己没续板，W 的 7 板不是 C 的破壁");
        assertProbe(on(pts, "2026-09-05"), "W");
        assertQuiet(on(pts, "2026-09-06"), "W 9.05 那次试探当天刚判失败，同一级上不重复记试探");
        assertEquals(Integer.valueOf(7), on(pts, "2026-09-06").getCeiling());
    }

    @Test
    void breaks_dragonLiftsItsOwnLineAndItsDepartureIsTheBreak() {
        // 9.01 X 的 4 板起一级，钉 4−1=3 个交易日（9.01~9.03），期间只记到 9.02/9.03 的 3 板；
        // 9.04 降到 3 板、定线票换成记下这个高度的 A → 老龙头 X 这天回榜 4 板，在这一版算"别的票追平 3 板线"
        // =试探，9.07 它续到 5 板就是破壁成功（prev=3），此后它是龙、线跟它抬到 5。
        // 9.08 X 掉榜：它这次破壁的断板日就是下一级的第 1 天，线抬回它的 5 板钉 5−1=4 个交易日，
        // Y 的 5 板算追平、9.09 它 6 板破壁（真实版=爱丽家居 7.30→8.06 那种在高位一路续板的龙）。
        List<TiantiVO.HeightPoint> pts = java.util.Arrays.asList(
                pt("2026-09-01", 4, "X"),
                pt("2026-09-02", 3, "A"),
                pt("2026-09-03", 3, "B"),
                pt("2026-09-04", 4, "X"),
                pt("2026-09-07", 5, "X"),
                pt("2026-09-08", 5, "Y"),
                pt("2026-09-09", 6, "Y"));
        TiantiService.detectBreaks(pts);

        assertEquals(Integer.valueOf(4), on(pts, "2026-09-03").getCeiling(), "这一级还钉在 X 的 4 板上");
        assertEquals(Integer.valueOf(3), on(pts, "2026-09-04").getCeiling(),
                "钉满降到记录到的 3 板，定线票换成记下 3 板的 A");
        assertProbe(on(pts, "2026-09-04"), "X", "X 已经不是这一级的定线票，它回榜加板就是追平 3 板线");
        assertBreak(on(pts, "2026-09-07"), 3, "X");
        assertEquals(Integer.valueOf(5), on(pts, "2026-09-07").getCeiling(), "破壁成功当天 X 已经 5 板，线跟它抬");
        assertNull(on(pts, "2026-09-07").getIsChaos(), "X 还在榜，这一轮归它");
        assertNull(on(pts, "2026-09-07").getIsProbe(), "破壁当天不再另起一次试探");
        assertEquals(Integer.valueOf(5), on(pts, "2026-09-08").getCeiling(), "X 掉榜=断板，线抬回它的 5 板钉着");
        assertEquals(Boolean.TRUE, on(pts, "2026-09-08").getIsChaos(), "断板当天就是新的一级的第 1 天");
        assertProbe(on(pts, "2026-09-08"), "Y");
        assertBreak(on(pts, "2026-09-09"), 5, "Y");
    }

    @Test
    void breaks_retrySwitchesStockOnTheSameLine() {
        // 9.04 降到记录到的 3 板，C 这天 4 板追平=试探，并顺手当上这一级的定线票；
        // 9.05 C 还在榜且把线抬走（定线票再创新高=直接接棒成龙头，所以那天没有 ◆）；
        // 9.06 C 掉榜 → 线抬回它的 4 板重钉，换 E 追平才再记试探、9.07 E 续板成功
        List<TiantiVO.HeightPoint> pts = java.util.Arrays.asList(
                pt("2026-09-01", 4, "X"),
                pt("2026-09-02", 3, "A"),
                pt("2026-09-03", 3, "B"),
                pt("2026-09-04", 4, "C"),
                pt("2026-09-05", 4, "C"),
                pt("2026-09-06", 4, "E"),
                pt("2026-09-07", 5, "E"));
        TiantiService.detectBreaks(pts);

        assertProbe(on(pts, "2026-09-04"), "C");
        assertQuiet(on(pts, "2026-09-05"), "C 接棒成在位龙，在册期间不判试探");
        assertProbe(on(pts, "2026-09-06"), "E");
        assertBreak(on(pts, "2026-09-07"), 4, "E");
    }

    @Test
    void breaks_lineStepsDownInsteadOfHangingOnAnUnreachableHigh() {
        // v6 的利仁科技 8 板线能从 6 月一直悬到 7.10，当时靠 25 个自然日的 TTL 兜底；v11 不需要 TTL：
        // 9.01 X 的 4 板起一级只钉 4−1=3 个交易日，钉满就降到这期间没追平线的那些天里的最高板 3 板。
        // 之后每天最高恰好 3 板＝正好追平这条线，于是既记不到更低的高度、也掉不下去——原地一级级重钉。
        List<TiantiVO.HeightPoint> pts = new java.util.ArrayList<>(java.util.Arrays.asList(
                pt("2026-09-01", 4, "X"),
                pt("2026-09-02", 3, "A"),
                pt("2026-09-03", 3, "B")));
        for (LocalDate d = LocalDate.parse("2026-09-04"); !d.isAfter(LocalDate.parse("2026-10-09"));
                d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                pts.add(pt(d.toString(), 3, "L"));
            }
        }
        TiantiService.detectBreaks(pts);

        assertEquals(Integer.valueOf(4), on(pts, "2026-09-03").getCeiling(), "第 3 个交易日还钉在 X 的 4 板上");
        assertEquals(Integer.valueOf(3), on(pts, "2026-09-04").getCeiling(),
                "钉满就降到记录到的 3 板，不会悬在没人够得着的 4 板上等 TTL");
        assertProbe(on(pts, "2026-09-04"), "L", "L 3 板追平降下来的 3 板线");
        for (String d : new String[] { "2026-09-07", "2026-09-28", "2026-10-09" }) {
            assertEquals(Integer.valueOf(3), on(pts, d).getCeiling(), d + " 天天 3 板追平，原地重钉这一级");
            assertEquals("L", on(pts, d).getLineStock().getCode(), "L 就是这一级的定线票");
            assertQuiet(on(pts, d), "定线票自己守线不算新的试探");
            assertEquals(Integer.valueOf(4), on(pts, d).getOldDragonHeight(), "色块报的旧龙高度还是 X 的 4 板");
        }
        assertEquals(Boolean.TRUE, on(pts, "2026-10-09").getIsChaos(), "没人破壁成功，阶梯就一直挂着");
    }

    /**
     * 真实曲线整段回放。{@code /market/break_curve_2026.json} 是从 height-range 端点导出的
     * 2026-04-01~09-30：每天最高板 + 并列最高板名单 + 4 板以上的连板名单。
     *
     * <p>从 4 月起头而不是从 7 月起头：阶梯是从取数首日一路走过来的，起点太晚相位就不对（试过 5.20 起头，
     * 7 月的标记整体错位）。名单裁到 4 板以上是为了压文件体积，整段回放下来 7~9 月的标记与全名单逐笔一致。
     *
     * <p>钉死他手算核对的那几个日子：恒尚节能 7.06◆/7.07★、立新能源 7.22◆/7.23★（追平哈药股份 7.16 记下的
     * 5 板线）、爱丽家居 7.28◆/7.29★、深中华Ａ 8.28◆、华瓷股份 9.22★；闽东电力 9.16◆ 但没兑现，
     * 9.24 新华文轩<b>不给</b>标记（v13 起失败试探的高度也记账，那天线还钉在 6 板上）。
     */
    @Test
    void breaks_realCurve_datedAnchors() throws Exception {
        List<TiantiVO.HeightPoint> pts = loadCurve("/market/break_curve_2026.json");
        TiantiService.detectBreaks(pts);

        assertProbe(on(pts, "2026-07-06"), "603137"); // 恒尚节能追平降到的 4 板线
        assertBreak(on(pts, "2026-07-07"), 4, "603137");
        // 恒尚 7.09 打完 8 板、7.10 掉榜 → 线抬到 8 板钉 8−1=7 个交易日（7.10~7.20），7.21 才降一级
        assertEquals(Integer.valueOf(8), on(pts, "2026-07-10").getCeiling(), "断板当天就把线抬到恒尚的 8 板");
        assertQuiet(on(pts, "2026-07-16"), "哈药股份的 5 板追不上 8 板线，只进降线记录");
        assertEquals(Integer.valueOf(8), on(pts, "2026-07-20").getCeiling(), "第 7 个交易日还钉着");
        assertEquals(Integer.valueOf(5), on(pts, "2026-07-21").getCeiling(), "钉满降到记录到的 5 板");
        assertEquals("600664", on(pts, "2026-07-21").getLineStock().getCode()); // 哈药股份
        assertProbe(on(pts, "2026-07-22"), "001258"); // 立新能源
        assertBreak(on(pts, "2026-07-23"), 5, "001258");
        assertQuiet(on(pts, "2026-07-27"), "立新 7.24 断板 → 线钉在 6 板，长缆的 5 板追不上");
        assertProbe(on(pts, "2026-07-28"), "603221"); // 爱丽家居 6 板追平
        assertBreak(on(pts, "2026-07-29"), 6, "603221");

        // 他手算的那条阶梯：深中华Ａ 8.28 打满 7 板、8.31 掉榜 → 7−1=6 个交易日钉在 7 板
        assertProbe(on(pts, "2026-08-28"), "000017",
                "深中华Ａ 7 板越过来到它名下的 6 板线——这级是阶梯从爱丽家居那轮降下来的，不是它筑的壁，算试探");
        assertNull(on(pts, "2026-08-31").getIsBreak(), "8.31 它掉榜 = 这次试探没兑现，不补 ★");
        assertEquals(Integer.valueOf(7), on(pts, "2026-08-28").getCeiling(), "试探之后周期归它，线跟它抬到 7 板");
        assertNull(on(pts, "2026-08-28").getIsChaos());
        for (String d : new String[] { "2026-08-31", "2026-09-02", "2026-09-04", "2026-09-07" }) {
            assertEquals(Integer.valueOf(7), on(pts, d).getCeiling(), d + " 还钉在深中华Ａ的 7 板上");
            assertEquals(Integer.valueOf(7), on(pts, d).getOldDragonHeight());
            assertEquals(Boolean.TRUE, on(pts, d).getIsChaos());
        }
        assertProbe(on(pts, "2026-09-01"), "002084"); // 海鸥住工 7 板追平这条线
        assertQuiet(on(pts, "2026-09-02"), "海鸥没续板=破壁失败，只许换票");
        // v13：海鸥那次失败的 7 板也记进降线高度 → 9.08 这一级钉满仍然顶在 7 上，不许降到 6。
        // 他 9.27 手算的「9.8 开始降到 9.7 的 6」就是被这条改掉的。
        assertEquals(Integer.valueOf(7), on(pts, "2026-09-08").getCeiling(),
                "记不到比 7 更低的高度（海鸥追平过），原地再钉一级");
        assertEquals(Integer.valueOf(7), on(pts, "2026-09-15").getCeiling(), "再钉的 7−1=6 个交易日没人够到 7");
        assertQuiet(on(pts, "2026-09-15"), "5 板够不着 7 板线（v12 在这里给闽东电力 ◆）");
        // 这 6 天没人追平，记到的最高只有 5 板 → 阶梯从 7 直接跳到 5，中间那级 6 被抹掉
        assertEquals(Integer.valueOf(5), on(pts, "2026-09-16").getCeiling(), "钉满降到记录到的 5 板");
        assertProbe(on(pts, "2026-09-16"), "000993"); // 闽东电力 6 板越过 5 板线才算试探
        assertNull(on(pts, "2026-09-17").getIsBreak(), "闽东 9.17 掉榜=没兑现，这一版它没有 ★（v12 是 ★9.16）");
        assertProbe(on(pts, "2026-09-17"), "605058"); // 澳弘电子 5 板追平（v12 里这天没标记）
        assertProbe(on(pts, "2026-09-21"), "001216"); // 华瓷股份 5 板追平
        assertBreak(on(pts, "2026-09-22"), 5, "001216"); // 9.22 续上 6 板=破壁成功（v11 删掉的那颗 ★ 回来了）
        // 华瓷成了在位龙，9.23 掉榜 → 按它的 6 板重钉 6−1=5 个交易日
        assertEquals(Integer.valueOf(6), on(pts, "2026-09-24").getCeiling(), "他要的就是这个：破壁线还钉在 6");
        assertQuiet(on(pts, "2026-09-24"), "新华文轩只有 5 板，够不着 6 板线——v12 在这里给它的 ◆ 就是要撤掉的");

        // 色块 = 整条阶梯：只要没有票破壁成功、线还在钉或在降，就一直在色块里
        assertEquals(Boolean.TRUE, on(pts, "2026-07-21").getIsChaos(), "降一级之后还在同一轮阶梯上");
        assertEquals(Boolean.TRUE, on(pts, "2026-07-22").getIsChaos());
        assertEquals(Integer.valueOf(8), on(pts, "2026-07-22").getOldDragonHeight(),
                "ceiling 已经降到 5，色块报的旧龙高度还是恒尚的 8 板");
        assertEquals(Integer.valueOf(7), on(pts, "2026-09-18").getOldDragonHeight(),
                "线已经跳到 5 板，挂账的旧龙高度还是深中华Ａ的 7 板");
        assertNull(on(pts, "2026-09-22").getIsChaos(), "华瓷这天破壁成功，阶梯停住");

        int probes = 0;
        int breaks = 0;
        for (TiantiVO.HeightPoint p : pts) {
            if (p.getTradeDate().toString().compareTo("2026-07-01") < 0) {
                continue;
            }
            if (p.getIsProbe() != null) {
                probes++;
            }
            if (p.getIsBreak() != null) {
                breaks++;
            }
        }
        // 数量级也钉住：规则一改就把标记刷成一片、或一个都不给，这里都会先红。
        // 阶梯把线在高处多钉几天，够得着线的日子少了，所以 v11 比 v10 明显稀疏（21/10 → 7/4）；
        // v12 补回挂着线的票自己爬过这条线（8.28 深中华Ａ）→ 7 → 8。
        // v13 总数没动（还是 8/4），动的是分布：闽东的 ★9.16 换成 ★9.22 华瓷，澳弘 9.17 的 ◆ 回来了。
        assertEquals(8, probes);
        assertEquals(4, breaks);
    }

    /** 读 break_curve_2026.json：{@code [日期, 最高板, [[代码,名称]…], [[板高,代码,名称]…]]}。 */
    private static List<TiantiVO.HeightPoint> loadCurve(String path) throws Exception {
        List<TiantiVO.HeightPoint> out = new java.util.ArrayList<>();
        try (java.io.InputStream in = TiantiServiceTest.class.getResourceAsStream(path)) {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
            for (com.fasterxml.jackson.databind.JsonNode row : root) {
                TiantiVO.HeightPoint p = new TiantiVO.HeightPoint();
                p.setTradeDate(LocalDate.parse(row.get(0).asText()));
                p.setMaxHeight(row.get(1).asInt());
                List<TiantiVO.HeightStock> stocks = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode s : row.get(2)) {
                    stocks.add(new TiantiVO.HeightStock(s.get(0).asText(), s.get(1).asText()));
                }
                stocks.sort(java.util.Comparator.comparing(TiantiVO.HeightStock::getCode));
                p.setStocks(stocks);
                p.setStockCount(stocks.size());
                List<TiantiVO.LadderStock> ladder = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode r : row.get(3)) {
                    ladder.add(new TiantiVO.LadderStock(r.get(0).asInt(), r.get(1).asText(),
                            r.get(2).asText()));
                }
                p.setLadder(ladder);
                out.add(p);
            }
        }
        return out;
    }

    @Test
    void chaos_notifiesOldDragonHeightForMinusOneTradeDays() {
        // X 爬到 5 板接棒成在位龙，9.04 掉榜：断板当天就是这一级的第 1 天，共 H−1=4 个交易日
        // （9.04、9.07、9.08、9.09）都报"旧龙 5 板"，且这四天破壁线就钉在 5 板上不动。
        // 9.10 钉满降到这期间没追平线的那些天里最高的 4 板——色块不在此收尾：只要没人破壁成功，
        // 整条阶梯都在"旧龙的高度还没被收复"这件事里（v10 是降一级就算周期走完）。
        List<TiantiVO.HeightPoint> pts = java.util.Arrays.asList(
                pt("2026-09-01", 3, "X"),
                pt("2026-09-02", 4, "X"),
                pt("2026-09-03", 5, "X"),
                pt("2026-09-04", 4, "A"),
                pt("2026-09-07", 4, "B"),
                pt("2026-09-08", 4, "C"),
                pt("2026-09-09", 4, "D"),
                pt("2026-09-10", 4, "E"),
                pt("2026-09-11", 4, "F"),
                pt("2026-09-14", 4, "G"));
        TiantiService.detectBreaks(pts);

        assertEquals(Boolean.TRUE, on(pts, "2026-09-01").getIsChaos(), "取数首日前没有旧龙，也算在阶梯的第一级上");
        assertNull(on(pts, "2026-09-03").getIsChaos(), "X 还在加板把线抬走，这一轮归它");
        assertEquals(Boolean.TRUE, on(pts, "2026-09-04").getIsChaos(), "断板当天就是这一级的第 1 天");
        for (String d : new String[] { "2026-09-07", "2026-09-08", "2026-09-09" }) {
            assertEquals(Boolean.TRUE, on(pts, d).getIsChaos(), d + " 还在 X 的 5 板上钉着");
            assertEquals(Integer.valueOf(5), on(pts, d).getOldDragonHeight());
            assertEquals(Integer.valueOf(5), on(pts, d).getCeiling(), d + " 破壁线钉在旧龙 5 板上，不跟着市场走");
            assertQuiet(on(pts, d), "市场最高只有 4 板，够不着这条线");
        }
        assertEquals(Integer.valueOf(4), on(pts, "2026-09-10").getCeiling(), "钉满降到记录到的 4 板");
        assertEquals(Integer.valueOf(5), on(pts, "2026-09-10").getOldDragonHeight(),
                "H 不跟着降：还没收复的是 X 的 5 板，不是这条 4 板线");
        assertEquals(Boolean.TRUE, on(pts, "2026-09-10").getIsChaos(), "降一级还在同一轮阶梯上，色块不中断");
        assertProbe(on(pts, "2026-09-10"), "E", "线降到 4 板当天 E 就追平了");
    }

    @Test
    void minAbsBreak_iceAgeFloors() {
        // v6 的门槛已改成"另一只票追平冻结线"，这条绝对高度兜底目前没接进判定，先留作调参口径：
        // 冰点期市场最高 2 板时，2 板并列也会算试探，要不要压回 4 板等他定
        assertEquals(Integer.valueOf(4), TiantiService.minAbsBreak(2));
        assertEquals(Integer.valueOf(5), TiantiService.minAbsBreak(4));
        assertEquals(Integer.valueOf(6), TiantiService.minAbsBreak(5));
    }
}