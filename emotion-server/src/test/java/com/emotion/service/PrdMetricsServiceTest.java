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
        return new PrdMetricsService(null, null);
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
}
