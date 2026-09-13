package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.emotion.entity.MarketStock;
import com.emotion.entity.Theme;

/**
 * {@link PrdMetricsService#aggregate} 纯聚合单测：空间板归属、龙头错位、成交额聚集度（池内口径）、
 * 生命周期阶段与板块内龙头。不连 DB（mapper 传 null——题材匹配 NPE 被其内部 catch 成"无题材行"）。
 */
class PrdMetricsServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 11);

    private static MarketStock zt(String code, String name, String industry, int board,
                                  double changePct, Double amount) {
        MarketStock s = new MarketStock();
        s.setTradeDate(D);
        s.setCode(code);
        s.setName(name);
        s.setPool(MarketStock.POOL_LIMIT_UP);
        s.setIndustry(industry);
        s.setConsecutive(board);
        s.setChangePct(BigDecimal.valueOf(changePct));
        if (amount != null) {
            s.setAmount(BigDecimal.valueOf(amount));
        }
        return s;
    }

    private PrdMetricsService service() {
        return new PrdMetricsService(null, null, null);
    }

    /**
     * 9/11 元件案例：市场空间板 4 板（瑞尔特·家居用品）不在主线；主线元件 9/40 涨停、最高仅 2 板
     * （超声电子）；持续性 1 天=萌芽。聚合器必须同时产出"市场总龙头"和"板块内龙头"两个事实。
     */
    @Test
    void aggregate_yuanjian_spaceBoardOutsideMain() {
        List<MarketStock> zt = new ArrayList<>();
        zt.add(zt("002790", "瑞尔特", "家居用品", 4, 10.05, 400.0));
        zt.add(zt("000823", "超声电子", "元件", 2, 10.0, 100.0));
        for (int i = 1; i <= 8; i++) {
            zt.add(zt(String.format("0020%02d", i), "元件首板" + i, "元件", 1, 10.0, 100.0));
        }
        // 30 只其他行业（10 行业 ×3，每只 90 元成交额），保证最热仍是元件
        for (int i = 0; i < 30; i++) {
            zt.add(zt(String.format("600%03d", i), "其他" + i, "其他" + (i / 3), 1, 5.0, 90.0));
        }

        Map<LocalDate, Map<String, Integer>> history = new HashMap<>();
        Map<String, Integer> today = new HashMap<>();
        today.put("元件", 9); // ≥ HOT_ZT_THRESHOLD(5) → 今日算 1 个热度日
        history.put(D, today);

        PrdMetricsService.Snapshot snap = service().aggregate(D, zt, Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(),
                history, 1L, null);

        assertEquals("元件", snap.mainIndustry);
        assertEquals(40, snap.ztTotal);
        assertEquals(9, snap.mainZt);
        assertEquals(4, snap.maxBoard);
        assertEquals(2, snap.mainMaxBoard);
        assertEquals("002790", snap.zongLong.getCode(), "全市场总龙头=瑞尔特 4 板");
        assertEquals("000823", snap.mainLeader.getCode(), "板块内龙头=超声电子 2 板");
        assertFalse(snap.spaceBoardInMain, "空间板 H=4 在家居用品，不在元件");
        assertFalse(snap.dragonAligned, "总龙头与主线错位");
        assertEquals(1, snap.persistenceDays.intValue());
        assertEquals("萌芽", snap.lifecycleStage);

        Map<String, BigDecimal> m = snap.metrics;
        assertEquals(0, new BigDecimal("22.5").compareTo(m.get("zt_gather_pct")));
        assertEquals(0, new BigDecimal("50").compareTo(m.get("height_gather_pct")), "板数比仍算 50%（砍半由引擎做）");
        assertEquals(0, BigDecimal.ZERO.compareTo(m.get("space_board_in_main")));
        assertEquals(0, BigDecimal.ONE.compareTo(m.get("dragon_misalign")));
        assertEquals(0, BigDecimal.ONE.compareTo(m.get("main_sector_active")));
        assertEquals(0, new BigDecimal("50").compareTo(m.get("mainline_stage_cap")), "萌芽天花板 50");
        // 成交额：元件 900 / 全池 4000 = 22.5%（池内口径）
        assertEquals(0, new BigDecimal("22.5").compareTo(m.get("amount_gather_pct")));
        // 无题材行：不产硬度键（引擎催化剂策略在 main_sector_active=1 下给缺省 50）
        assertFalse(m.containsKey("catalyst_hardness"));
    }

    /** 空间板就在主线行业：归属旗标=1、不产错位键，板块内龙头=总龙头。 */
    @Test
    void aggregate_spaceBoardInsideMain_isAligned() {
        List<MarketStock> zt = new ArrayList<>();
        zt.add(zt("300001", "主线龙", "元件", 3, 10.0, 500.0));
        zt.add(zt("300002", "跟风A", "元件", 1, 10.0, 100.0));
        zt.add(zt("300003", "跟风B", "元件", 1, 9.9, 100.0));
        zt.add(zt("600001", "他行业", "食品", 1, 5.0, 100.0));

        Map<LocalDate, Map<String, Integer>> history = new HashMap<>();
        Map<String, Integer> today = new HashMap<>();
        today.put("元件", 3); // < 5，热度日不计数 → 持续性 0 天，仍算萌芽
        history.put(D, today);

        PrdMetricsService.Snapshot snap = service().aggregate(D, zt, Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(),
                history, 1L, null);

        assertEquals("元件", snap.mainIndustry);
        assertEquals(3, snap.maxBoard);
        assertTrue(snap.spaceBoardInMain);
        assertTrue(snap.dragonAligned);
        assertEquals("300001", snap.mainLeader.getCode());
        assertEquals("300001", snap.zongLong.getCode());
        assertEquals(0, BigDecimal.ONE.compareTo(snap.metrics.get("space_board_in_main")));
        assertFalse(snap.metrics.containsKey("dragon_misalign"));
        assertEquals("萌芽", snap.lifecycleStage);
    }

    /** 迁移前历史行 amount 全 null：成交额聚集度不产键（=未评），其余要素照常。 */
    @Test
    void aggregate_amountColumnAllMissing_keyAbsent() {
        List<MarketStock> zt = new ArrayList<>();
        zt.add(zt("300001", "主线龙", "元件", 2, 10.0, null));
        zt.add(zt("300002", "跟风A", "元件", 1, 10.0, null));
        zt.add(zt("600001", "他行业", "食品", 1, 5.0, null));

        PrdMetricsService.Snapshot snap = service().aggregate(D, zt, Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(),
                new HashMap<LocalDate, Map<String, Integer>>(), 1L, null);

        assertEquals("元件", snap.mainIndustry);
        assertNull(snap.amountGatherPct);
        assertFalse(snap.metrics.containsKey("amount_gather_pct"), "amount 全缺=未评，绝不兜 0");
        assertEquals(0, new BigDecimal("66.67").compareTo(snap.metrics.get("zt_gather_pct")));
    }

    @Test
    void lifecycleStage_rules() {
        assertEquals("退潮", PrdMetricsService.lifecycleStage(5, 12, "BREAK", 3, 4));
        assertEquals("退潮", PrdMetricsService.lifecycleStage(4, 9, "HOLD", 3, 4), "涨停腰斩也退潮");
        assertEquals("萌芽", PrdMetricsService.lifecycleStage(6, 0, "PROMOTE", 1, 4));
        assertEquals("确认", PrdMetricsService.lifecycleStage(6, 0, "PROMOTE", 2, 4));
        assertEquals("扩散", PrdMetricsService.lifecycleStage(6, 0, "PROMOTE", 3, 4));
        assertEquals("亢奋", PrdMetricsService.lifecycleStage(6, 0, "PROMOTE", 5, 4));
        assertEquals("亢奋", PrdMetricsService.lifecycleStage(6, 0, "PROMOTE", 1, 7), "H≥7 直接亢奋");
    }

    // ================= 双轨 v0.2：雷达 + 评分对象选择（人工标记 > ≥3天自动主线 > 当日候选榜首） =================

    /** 构件：今日 元件zt_max(今日最热但仅1天) + 旅游≥3天。history 给 旅游 3 个连续热度日。 */
    private Object[] dualTrackFixture() {
        List<MarketStock> zt = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            zt.add(zt("00200" + i, "元件" + i, "元件", 1, 10.0, 100.0));
        }
        for (int i = 1; i <= 5; i++) {
            zt.add(zt("60000" + i, "旅游" + i, "旅游", 1, 10.0, 100.0));
        }
        zt.add(zt("600099", "旅游龙", "旅游", 4, 10.0, 500.0)); // 全市场空间板=旅游
        LocalDate d1 = D.minusDays(1);
        LocalDate d2 = D.minusDays(2);
        Map<LocalDate, Map<String, Integer>> history = new HashMap<>();
        Map<String, Integer> today = new HashMap<>();
        today.put("元件", 8);
        today.put("旅游", 6); // 旅游 今日≥5 → 热度日 +1
        history.put(D, today);
        Map<String, Integer> y1 = new HashMap<>();
        y1.put("旅游", 5);
        history.put(d1, y1);
        Map<String, Integer> y2 = new HashMap<>();
        y2.put("旅游", 5);
        history.put(d2, y2);
        return new Object[]{zt, history};
    }

    /** 评分对象选择：今日最热=元件(1天)，但 ≥3天 的旅游才是主线 → D2 对象=旅游（非今日最热）。 */
    @Test
    void aggregate_objectSelection_prefersConfirmedMainline() {
        Object[] fx = dualTrackFixture();
        PrdMetricsService.Snapshot snap = service().aggregate(D, (List<MarketStock>) fx[0],
                Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), (Map<LocalDate, Map<String, Integer>>) fx[1], 1L, null);

        assertEquals("元件", snap.radarTopIndustry, "雷达榜首=今日最热");
        assertEquals("旅游", snap.autoMainlineIndustry, "自动主线=≥3天行业");
        assertEquals("旅游", snap.mainIndustry, "评分对象=已确认主线，而非今日最热");
        assertTrue(snap.mainlineConfirmed);
        assertFalse(snap.manuallyMarked);
        assertEquals(3, snap.persistenceDays.intValue());
        assertEquals("600099", snap.zongLong.getCode(), "总龙头=旅游龙4板");
        // 雷达排序：zt 降序 → 元件(8) 在 旅游(6) 前
        assertEquals("元件", snap.radar.get(0).industry);
        assertEquals("旅游", snap.radar.get(1).industry);
        assertEquals("MAIN", snap.radar.get(1).flag);
        assertEquals("NEW", snap.radar.get(0).flag);
    }

    /** 人工主线标记优先于 ≥3天自动主线：把 1 天的元件 标为主线。 */
    @Test
    void aggregate_objectSelection_manualMarkOverridesAuto() {
        Object[] fx = dualTrackFixture();
        PrdMetricsService.Snapshot snap = service().aggregate(D, (List<MarketStock>) fx[0],
                Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), (Map<LocalDate, Map<String, Integer>>) fx[1], 1L, null,
                "元件");

        assertEquals("元件", snap.mainIndustry, "人工标记优先于自动主线");
        assertTrue(snap.mainlineConfirmed);
        assertTrue(snap.manuallyMarked);
        assertEquals(1, snap.persistenceDays.intValue());
    }

    /** 人工标记指向当日无涨停的行业：忽略，回退到当日候选榜首（无主线）。 */
    @Test
    void aggregate_objectSelection_manualMarkUnknownIndustryFallsBack() {
        Object[] fx = dualTrackFixture();
        PrdMetricsService.Snapshot snap = service().aggregate(D, (List<MarketStock>) fx[0],
                Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), (Map<LocalDate, Map<String, Integer>>) fx[1], 1L, null,
                "不存在行业");

        assertEquals("旅游", snap.mainIndustry, "人工标记行业当日无涨停 → 忽略，退自动主线");
        assertFalse(snap.manuallyMarked);
    }

    /** 雷达 flag 三档：元件1天 NEW、旅游≥3天 MAIN，中间再补一个 2 天 WATCH。 */
    @Test
    void aggregate_radar_flagsThreeTiers() {
        Object[] fx = dualTrackFixture();
        List<MarketStock> zt = (List<MarketStock>) fx[0];
        LocalDate d1 = D.minusDays(1);
        LocalDate d2 = D.minusDays(2);
        Map<LocalDate, Map<String, Integer>> history = (Map<LocalDate, Map<String, Integer>>) fx[1];
        // 补电力：今日5只(≥5热度日)，且昨日、前日都≥5 → 2 天？不行；让电力昨日1天 → 今日算2天当 WATCH 需连续2日含今天
        for (int i = 1; i <= 5; i++) {
            zt.add(zt("30001" + i, "电力" + i, "电力", 1, 10.0, 100.0));
        }
        history.get(D).put("电力", 5);
        Map<String, Integer> e1 = new HashMap<>(history.get(d1));
        e1.put("电力", 5);
        history.put(d1, e1); // 电力连续2日(today+d1) → WATCH
        Map<String, Integer> e2 = new HashMap<>(history.get(d2));
        history.put(d2, e2);

        PrdMetricsService.Snapshot snap = service().aggregate(D, zt, Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(), history, 1L, null);

        assertEquals("NEW", snap.radar.get(0).flag);   // 元件(8) NEW
        assertEquals("MAIN", snap.radar.get(1).flag);  // 旅游(6) MAIN
        assertEquals("WATCH", snap.radar.get(2).flag); // 电力(5) WATCH
    }

    /** §3 龙头去重：昨炸板今回封、且该股已是主线中军 → 从反包列表剔除。 */
    @Test
    void aggregate_fanBao_dedupsAgainstUsedRoles() {
        List<MarketStock> zt = new ArrayList<>();
        zt.add(zt("000001", "元件龙", "元件", 3, 10.0, 300.0));   // 总龙头
        zt.add(zt("000002", "元件B", "元件", 2, 10.0, 200.0));   // 中军
        zt.add(zt("000003", "首板C", "元件", 1, 10.0, 100.0));
        zt.add(zt("000004", "首板D", "元件", 1, 10.0, 100.0));
        zt.add(zt("000009", "新票F", "元件", 1, 10.0, 100.0));   // 昨炸今日重新封 → 真反包

        // 昨炸板池：元件B（今天是中军，应剔除）、新票F（今天是涨停，保留）
        List<MarketStock> prevZB = new ArrayList<>();
        MarketStock zbB = zt("000002", "元件B", "元件", 2, -3.0, 200.0);
        zbB.setPool(MarketStock.POOL_BROKEN);
        MarketStock zbF = zt("000009", "新票F", "元件", 1, -5.0, 100.0);
        zbF.setPool(MarketStock.POOL_BROKEN);
        prevZB.add(zbB);
        prevZB.add(zbF);

        Map<LocalDate, Map<String, Integer>> history = new HashMap<>();
        history.put(D, new HashMap<String, Integer>());

        PrdMetricsService.Snapshot snap = service().aggregate(D, zt, Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), prevZB, history, 1L, null);

        assertEquals(1, snap.fanBao.size(), "反包已剔除总龙头/中军角色，只留新票F");
        assertEquals("000009", snap.fanBao.get(0).getCode());
    }

    /** 活跃口径：按"连续活跃"计数（涨停≥3 就算在持续）。10 号 5 家=第 1 天，11 号 4 家=第 2 天；今天 <3 才归零。 */
    @Test
    void consecutiveDays_countsConsecutiveActiveDays() {
        Map<LocalDate, Map<String, Integer>> h = new HashMap<>();
        Map<String, Integer> d10 = new HashMap<>();
        d10.put("电力", 5);
        Map<String, Integer> d11 = new HashMap<>();
        d11.put("电力", 4);
        h.put(D.minusDays(1), d10);
        h.put(D, d11);

        assertEquals(2, PrdMetricsService.consecutiveDays(h, D, "电力"), "10号持续第1天、11号4家仍活跃=第2天");
        d11.put("电力", 2);
        assertEquals(0, PrdMetricsService.consecutiveDays(h, D, "电力"), "今天2(<3真正走弱)归0");
        d11.put("电力", 5);
        assertEquals(2, PrdMetricsService.consecutiveDays(h, D, "电力"), "今天5+昨日5=2天");
    }

    /** 雷达区每行带题材关联：行业名匹配到 t_theme 则附题材名，未登记=null。 */
    @Test
    void aggregate_radar_carriesThemeAssociation() {
        List<MarketStock> zt = new ArrayList<>();
        zt.add(zt("000001", "元件A", "元件", 2, 10.0, 100.0));
        zt.add(zt("000002", "元件B", "元件", 1, 10.0, 100.0));
        zt.add(zt("600001", "电力A", "电力", 1, 5.0, 100.0));

        Map<LocalDate, Map<String, Integer>> history = new HashMap<>();
        history.put(D, new HashMap<String, Integer>());
        Map<String, Theme> industryTheme = new HashMap<>();
        Theme th = new Theme();
        th.setName("元件");
        industryTheme.put("元件", th);

        PrdMetricsService.Snapshot snap = service().aggregate(D, zt, Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(),
                history, 1L, null, null, industryTheme);

        assertEquals("元件", snap.radar.get(0).industry);
        assertEquals("元件", snap.radar.get(0).theme, "元件行附加题材名");
        assertEquals("电力", snap.radar.get(1).industry);
        assertNull(snap.radar.get(1).theme, "电力未登记题材=null");
    }

    /** 题材表：按题材归并板块表，同题材多行业合并涨停；未登记题材不进题材表。 */
    @Test
    void aggregate_radarThemes_groupsByTheme() {
        // 元件 2 家 + 电子（同样登记成"元件"题材）1 家 → 题材"元件" 涨停=3；电力(未登记题材)不进题材表
        List<MarketStock> zt = new ArrayList<>();
        zt.add(zt("000001", "元件A", "元件", 2, 10.0, 100.0));
        zt.add(zt("000002", "元件B", "元件", 1, 10.0, 100.0));
        zt.add(zt("300001", "电子C", "电子", 1, 10.0, 100.0));
        zt.add(zt("600001", "电力A", "电力", 1, 5.0, 100.0));

        Map<LocalDate, Map<String, Integer>> history = new HashMap<>();
        history.put(D, new HashMap<String, Integer>()); // 今天只需存在，持续天数不参与本断言
        Map<String, Theme> it = new HashMap<>();
        Theme tYJ = new Theme();
        tYJ.setName("元件");
        it.put("元件", tYJ);
        Theme el = new Theme();
        el.setName("元件");
        it.put("电子", el); // 电子也归到"元件"题材

        PrdMetricsService.Snapshot snap = service().aggregate(D, zt, Collections.<MarketStock>emptyList(),
                Collections.<MarketStock>emptyList(), Collections.<MarketStock>emptyList(),
                history, 1L, null, null, it);

        assertEquals(1, snap.radarThemes.size(), "只有「元件」一个题材进题材表");
        PrdMetricsService.RadarRow t = snap.radarThemes.get(0);
        assertEquals("元件", t.industry);
        assertEquals(3, t.zt, "元件+电子合并成题材涨停3家");
        assertEquals(2, t.maxBoard, "最高板取成员最大");
    }
}
