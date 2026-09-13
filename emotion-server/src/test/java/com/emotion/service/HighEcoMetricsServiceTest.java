package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.entity.Anchor;
import com.emotion.entity.MarketStock;
import com.emotion.market.HighEcoMetrics;
import com.emotion.market.SurveillanceKind;
import com.emotion.market.SurvivalMember;
import com.emotion.vo.HighEcoVO;

/**
 * D5 高位生态：纯判据 {@link HighEcoMetrics} + {@link HighEcoMetricsService#aggregate} 的内存 fixture 测试。
 *
 * <p>不连 DB、不联网：池行/人工阵眼/监管名单全手工构造。重点钉：行为五态、各档分数、
 * 多阵眼角色加权、无阵眼整支未评（不兜 0）、9/11 瑞尔特案例（子项1≈94、错位在）、
 * 死亡结构/监管无效信号与两条强制风控。
 */
class HighEcoMetricsServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 11);

    // ---------------- 纯判据 ----------------

    @Test
    void actionFiveStates() {
        // 晋级：昨 ZT 3 板，今 ZT 4 板
        assertEquals(HighEcoMetrics.JIN_JIA, HighEcoMetrics.actionOf(
                true, 4, false, false, false, true, 3));
        // 反包：昨不在 ZT，今 ZT
        assertEquals(HighEcoMetrics.FAN_BAO, HighEcoMetrics.actionOf(
                true, 2, false, false, false, false, null));
        // 核按钮：今 DT
        assertEquals(HighEcoMetrics.HE_PAN, HighEcoMetrics.actionOf(
                false, null, false, true, false, true, 4));
        // 核按钮：今 ZB 且大面
        assertEquals(HighEcoMetrics.HE_PAN, HighEcoMetrics.actionOf(
                false, null, true, false, true, true, 4));
        // 断板：今 ZB 但非大面
        assertEquals(HighEcoMetrics.DUAN_BAN, HighEcoMetrics.actionOf(
                false, null, true, false, false, true, 4));
        // 断板：三池皆无
        assertEquals(HighEcoMetrics.DUAN_BAN, HighEcoMetrics.actionOf(
                false, null, false, false, false, true, 4));
        // 抗跌：今 ZT 但昨也 ZT 板数对不上
        assertEquals(HighEcoMetrics.HANG_TIAO, HighEcoMetrics.actionOf(
                true, 4, false, false, false, true, 4));
    }

    @Test
    void highThresholdFixedAtFive() {
        // 高位固定 ≥5 板，与中位(3-4)/低位(2)互斥；即便 H<5 也不回落叠加中位
        assertEquals(5, HighEcoMetrics.highThreshold(7));
        assertEquals(5, HighEcoMetrics.highThreshold(5));
        assertEquals(5, HighEcoMetrics.highThreshold(4));
        assertEquals(5, HighEcoMetrics.highThreshold(3));
    }

    @Test
    void anchorOneScoreWeightsFourLeaves() {
        // 行为100/高度100/封板100/一致60 = 40+25+20+9 = 94（瑞尔特口径）
        assertEquals(Integer.valueOf(94),
                HighEcoMetrics.anchorOneScore(100, 100, 100, 60));
    }

    @Test
    void coalitionAndPressureLadders() {
        assertEquals(100, HighEcoMetrics.ratioScore(30));
        assertEquals(70, HighEcoMetrics.ratioScore(15));
        assertEquals(70, HighEcoMetrics.ratioScore(50));
        assertEquals(40, HighEcoMetrics.ratioScore(70));
        assertEquals(50, HighEcoMetrics.ratioScore(5));
        assertEquals(90, HighEcoMetrics.sealRatioScore(40));
        assertEquals(70, HighEcoMetrics.sealRatioScore(60));
        assertEquals(40, HighEcoMetrics.sealRatioScore(80));
        assertEquals(100, HighEcoMetrics.topUniqueScore(2));
        assertEquals(70, HighEcoMetrics.topUniqueScore(4));
        assertEquals(50, HighEcoMetrics.topUniqueScore(1));
        assertEquals(100, HighEcoMetrics.pressureCountScore(0));
        assertEquals(55, HighEcoMetrics.pressureCountScore(4));
        assertEquals(15, HighEcoMetrics.highSurvRatioScore(70));
        assertEquals(20, HighEcoMetrics.spreadScore(4));
    }

    @Test
    void feedbackFiveStates() {
        assertEquals(Integer.valueOf(0), HighEcoMetrics.feedbackScore(1, new BigDecimal("10")));
        assertEquals(Integer.valueOf(70), HighEcoMetrics.feedbackScore(0, new BigDecimal("10")));
        assertEquals(Integer.valueOf(85), HighEcoMetrics.feedbackScore(0, new BigDecimal("1")));
        assertEquals(Integer.valueOf(50), HighEcoMetrics.feedbackScore(0, new BigDecimal("-2")));
        assertEquals(Integer.valueOf(25), HighEcoMetrics.feedbackScore(0, new BigDecimal("-8")));
        assertNull(HighEcoMetrics.feedbackScore(0, null), "全员缺价=未评，不兜中位数");
    }

    @Test
    void signalsAndForcedRisk() {
        assertTrue(HighEcoMetrics.coalitionRisk(55.0, true, 40));
        assertFalse(HighEcoMetrics.coalitionRisk(55.0, false, 40));
        assertTrue(HighEcoMetrics.deathStructure(1, 50, 1));
        assertFalse(HighEcoMetrics.deathStructure(2, 50, 1));
        assertTrue(HighEcoMetrics.monitorIgnored(3, new BigDecimal("6"), 0));
        assertTrue(HighEcoMetrics.monitorWorks(1, null));
        assertTrue(HighEcoMetrics.monitorWorks(0, new BigDecimal("-8")));
        assertTrue(HighEcoMetrics.sectorPressure(3, 10.0));
        assertEquals(2, HighEcoMetrics.handoverLevel(false, 2, false));
        assertEquals(3, HighEcoMetrics.handoverLevel(false, 0, true));
        assertEquals(1, HighEcoMetrics.handoverLevel(false, 0, false));
        assertEquals(0, HighEcoMetrics.handoverLevel(true, 0, false));
        assertTrue(HighEcoMetrics.forceMonitoredTopBreak(true, true));
        assertTrue(HighEcoMetrics.forceDeath(1, 1));
        assertFalse(HighEcoMetrics.forceDeath(1, 2));
    }

    @Test
    void tierGapDetection() {
        java.util.Map<Integer, Integer> full = new java.util.HashMap<>();
        full.put(2, 8);
        full.put(3, 5);
        full.put(4, 1);
        assertFalse(HighEcoMetrics.hasTierGap(full, 4));
        full.remove(3);
        assertTrue(HighEcoMetrics.hasTierGap(full, 4));
    }

    // ---------------- aggregate 端到端 ----------------

    /** 9/11 瑞尔特：人工总龙 4 板晋级一字、家居≠日内核心元件；监管无事件 → 子项1≈94、压制/反馈未评。 */
    @Test
    void ruierteCase_anchorNinetyFourAndMisaligned() {
        Anchor ruierte = anchor("002790", "瑞尔特", "家居用品", AnchorService.ROLE_ZONG);
        // 今日 ZT 4 板、一字（无开板、09:25 首封）；昨 ZT 3 板
        MarketStock zt = zt("002790", "瑞尔特", "家居用品", 4, 10.05, 0, 92500);
        MarketStock prev = zt("002790", "瑞尔特", "家居用品", 3, 10.0, 0, 92500);
        // 梯队：2 板 8 只
        ztPool(8, "元件");
        List<MarketStock> todayZt = new ArrayList<>();
        todayZt.add(zt);
        for (int i = 0; i < 8; i++) {
            todayZt.add(zt("00000" + i, "跟风" + i, i < 3 ? "元件" : "其他", 2, 10.0, 0, 92500));
        }
        PrdMetricsService.Snapshot snap = new PrdMetricsService.Snapshot();
        snap.mainIndustry = "元件";
        snap.maxBoard = 4;
        snap.zongLong = zt;

        HighEcoMetricsService svc = new HighEcoMetricsService(null, null);
        HighEcoMetricsService.Build b = svc.aggregate(D, 4, "元件",
                todayZt, Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(),
                Arrays.asList(prev), todayZt,
                null, Arrays.asList(ruierte), Collections.<SurvivalMember>emptyList(), false, zt);

        HighEcoVO vo = b.getVo();
        assertTrue(vo.getAnchor().isConfigured());
        assertEquals(Integer.valueOf(94), vo.getAnchor().getScore(), "行为100+高度100+封板100+一致60=94");
        HighEcoVO.AnchorItem item = vo.getAnchor().getItems().get(0);
        assertEquals(HighEcoMetrics.JIN_JIA, item.getAction());
        assertNotNull(item.getConsistWarn(), "家居≠元件 应有错位告警");
        // 无监管事件窗：压制/反馈整支未评（不是 100）
        assertNull(vo.getPressure().getScore());
        assertNull(vo.getFeedback().getScore());
        assertFalse(b.getMetrics().containsKey("d5p_count"), "事件窗空不应发压制键");
        assertFalse(b.getMetrics().containsKey("d5f_nuke"), "事件窗空不应发反馈键");
        // 抱团仍出分（H=4 高位阈值=3，有 2 板梯队）
        assertNotNull(vo.getCoalition().getScore());
    }

    /** 无在位人工阵眼：阵眼子整支未评，但抱团/监管照常，D5 维分仍能由其余子项归一。 */
    @Test
    void noConfiguredAnchor_leavesAnchorUnscored() {
        MarketStock top = zt("600000", "空间板", "元件", 5, 10.0, 0, 92500);
        List<MarketStock> todayZt = new ArrayList<>();
        todayZt.add(top);
        PrdMetricsService.Snapshot snap = new PrdMetricsService.Snapshot();
        snap.mainIndustry = "元件";
        snap.maxBoard = 5;
        snap.zongLong = top;

        HighEcoMetricsService svc = new HighEcoMetricsService(null, null);
        HighEcoMetricsService.Build b = svc.aggregate(D, 5, "元件",
                todayZt, Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), todayZt,
                null, Collections.<Anchor>emptyList(), Collections.<SurvivalMember>emptyList(), false, top);

        assertFalse(b.getVo().getAnchor().isConfigured());
        assertFalse(b.getMetrics().containsKey("d5a_action"));
        // 监管压制在"拉过且 0 家"时出 100 分档（区别于没拉过=未评）
        SurvivalMember severe = member("600000", "空间板", "元件", 5, SurveillanceKind.SEVERE,
                D.minusDays(2), new BigDecimal("-9.9"));
        HighEcoMetricsService.Build b2 = svc.aggregate(D, 5, "元件",
                todayZt, Collections.<MarketStock>emptyList(),
                Arrays.asList(ztDt("600000")), Collections.<MarketStock>emptyList(), todayZt,
                null, Collections.<Anchor>emptyList(), Arrays.asList(severe), true, top);
        // 空间板唯一 + 高位被监管 + 核按钮 → 死亡结构信号 + 强制空仓
        assertTrue(b2.getMetrics().containsKey("d5_sig_death"));
        assertTrue(b2.getMetrics().containsKey("d5_force_death"));
        assertEquals(Integer.valueOf(0), b2.getVo().getFeedback().getScore());
    }

    /** 多阵眼按角色加权（总龙0.5/分支0.2/补涨0.2/反包0.1），旧 CYCLE 角色按总龙 0.5。 */
    @Test
    void multipleAnchorsWeightedByRole() {
        assertEquals(0.5, AnchorService.roleWeight(AnchorService.ROLE_ZONG), 1e-9);
        assertEquals(0.2, AnchorService.roleWeight(AnchorService.ROLE_FENZHI), 1e-9);
        assertEquals(0.2, AnchorService.roleWeight(AnchorService.ROLE_BUZHANG), 1e-9);
        assertEquals(0.1, AnchorService.roleWeight(AnchorService.ROLE_FANBAO), 1e-9);
        assertEquals(0.5, AnchorService.roleWeight(AnchorService.ROLE_CYCLE), 1e-9, "旧角色兼容为总龙");
        assertEquals(0.5, AnchorService.roleWeight(AnchorService.ROLE_LEADER), 1e-9);
    }

    // ---------------- D5 强信号守卫（否决权/无头折扣/强制封顶） ----------------

    @Test
    void guard_pureFunctions() {
        // 监管反馈否决权：核按钮≥1 → 压制分减半；未命中/未评原样
        assertNull(HighEcoMetrics.pressureAfterNukeVeto(null, 1));
        assertEquals(Integer.valueOf(44), HighEcoMetrics.pressureAfterNukeVeto(88, 1), "88×0.5=44");
        assertEquals(Integer.valueOf(88), HighEcoMetrics.pressureAfterNukeVeto(88, 0));
        // 无头抱团折扣：level≥3 → 抱团 ×0.85；否则原样
        assertNull(HighEcoMetrics.coalitionAfterHeadless(null, 3));
        assertEquals(Integer.valueOf(61), HighEcoMetrics.coalitionAfterHeadless(72, 3), "72×0.85≈61");
        assertEquals(Integer.valueOf(72), HighEcoMetrics.coalitionAfterHeadless(72, 2));
        // 强制风控封顶：force → 封到崩塌顶
        assertEquals(Integer.valueOf(20), HighEcoMetrics.cappedByForce(35, true));
        assertEquals(Integer.valueOf(35), HighEcoMetrics.cappedByForce(35, false));
        assertNull(HighEcoMetrics.cappedByForce(null, true));
    }

    /** 总龙断板+易主+监管核按钮 → 三守卫齐触发：否决权压制减半、无头折扣抱团、强制风控封顶崩塌。 */
    @Test
    void guard_vetoHeadlessAndForceCapOnAggregate() {
        Anchor dragon = anchor("600001", "金健米业", "农业", AnchorService.ROLE_ZONG);
        MarketStock prevDragon = zt("600001", "金健米业", "农业", 5, 9.9, 0, 92500);
        MarketStock topToday = zt("600002", "新空间板", "元件", 5, 10.0, 0, 92500);
        MarketStock low = zt("600004", "跟风", "元件", 2, 10.0, 0, 92500);
        List<MarketStock> todayZt = new ArrayList<>(Arrays.asList(topToday, low));
        List<MarketStock> prevZt = new ArrayList<>(Arrays.asList(prevDragon));
        List<MarketStock> todayDt = new ArrayList<>(Arrays.asList(ztDt("600003")));
        SurvivalMember severe = member("600003", "深中华", "元件", 2, SurveillanceKind.SEVERE,
                D.minusDays(2), new BigDecimal("-9.96"));

        PrdMetricsService.Snapshot snap = new PrdMetricsService.Snapshot();
        snap.mainIndustry = "元件";
        snap.maxBoard = 5;
        snap.zongLong = topToday;

        HighEcoMetricsService svc = new HighEcoMetricsService(null, null);
        HighEcoMetricsService.Build b = svc.aggregate(D, 5, "元件",
                todayZt, Collections.<MarketStock>emptyList(), todayDt, prevZt, todayZt,
                null, Arrays.asList(dragon), Arrays.asList(severe), true, topToday);

        HighEcoVO vo = b.getVo();
        // 三条守卫都命中
        assertNotNull(vo.getPressure().getAdjust(), "应有否决权说明");
        assertNotNull(vo.getCoalition().getAdjust(), "应有无头折扣说明");
        assertTrue(b.getMetrics().containsKey("d5_force_death"));
        assertNotNull(vo.getForceRisk());
        assertTrue(vo.getForceRisk().isTriggered());
        // 强制风控封顶到崩塌顶（≥20→危险），并有封顶 note
        assertNotNull(vo.getScore());
        assertTrue(vo.getScore() <= HighEcoMetrics.GUARD_FORCE_CAP,
                "force 触发应封顶，实际 " + vo.getScore());
        assertEquals("危险", vo.getLevel());
        assertTrue(vo.getNotes().stream().anyMatch(n -> n.contains("封顶")),
                "note 应含封顶说明，实际 " + vo.getNotes());
    }

    // ---------------- fixture helpers ----------------

    private static Anchor anchor(String code, String name, String industry, String role) {
        Anchor a = new Anchor();
        a.setId(1L);
        a.setStockCode(code);
        a.setStockName(name);
        a.setRole(role);
        a.setStartDate(D.minusDays(6));
        a.setEndDate(null);
        return a;
    }

    private static MarketStock zt(String code, String name, String industry, int board,
                                  double chg, int breakCount, int firstSeal) {
        MarketStock s = new MarketStock();
        s.setTradeDate(D);
        s.setCode(code);
        s.setName(name);
        s.setPool(MarketStock.POOL_LIMIT_UP);
        s.setIndustry(industry);
        s.setConsecutive(board);
        s.setChangePct(new BigDecimal(chg));
        s.setBreakCount(breakCount);
        s.setFirstSealTime(firstSeal);
        s.setSealAmount(new BigDecimal("100000000"));
        return s;
    }

    private static MarketStock ztDt(String code) {
        MarketStock s = new MarketStock();
        s.setTradeDate(D);
        s.setCode(code);
        s.setPool(MarketStock.POOL_LIMIT_DOWN);
        s.setBigLoss(1);
        return s;
    }

    private static List<MarketStock> ztPool(int n, String industry) {
        return new ArrayList<>();
    }

    private static SurvivalMember member(String code, String name, String industry, int board,
                                         SurveillanceKind kind, LocalDate ann, BigDecimal pct) {
        SurvivalMember m = new SurvivalMember();
        m.setCode(code);
        m.setName(name);
        m.setPct(pct);
        m.setEvents(Arrays.asList(SurvivalMember.ActiveEvent.of(kind, ann, 2, kind.days())));
        return m;
    }
}
