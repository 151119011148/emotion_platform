package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.entity.MarketStock;
import com.emotion.vo.IntradayCoreVO;

/**
 * 日内核心板块聚合口径。全内存 fixture，不碰 DB / Spring（service 用 null mapper 构造，
 * 只调纯函数 aggregate）。覆盖：行业聚齐、一字/T字靠 fbt+break_count（fbt 缺失=未知不算一字）、
 * 大面只挂 ZB、空行业行进总数不进排名、全首板梯队满分、龙头同板取涨幅、强度分算式与排名。
 */
class IntradayCoreServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 10);

    private final IntradayCoreService service = new IntradayCoreService(null);

    private static MarketStock zt(String code, String industry, Integer board, String changePct,
                                  Integer fbt, Integer breaks, String sealYuan) {
        MarketStock s = new MarketStock();
        s.setCode(code);
        s.setName(code);
        s.setIndustry(industry);
        s.setPool(MarketStock.POOL_LIMIT_UP);
        s.setConsecutive(board);
        s.setChangePct(changePct == null ? null : new BigDecimal(changePct));
        s.setFirstSealTime(fbt);
        s.setBreakCount(breaks);
        s.setSealAmount(sealYuan == null ? null : new BigDecimal(sealYuan));
        return s;
    }

    private static MarketStock zb(String code, String industry, Integer bigLoss) {
        MarketStock s = new MarketStock();
        s.setCode(code);
        s.setName(code);
        s.setIndustry(industry);
        s.setPool(MarketStock.POOL_BROKEN);
        s.setBigLoss(bigLoss);
        return s;
    }

    @Test
    void aggregate_两行业加空行业_排名与字段齐() {
        // 旅游及景点：3 板（龙头）+ 一字 2 板 + 开过板的 1 板；封单 10亿+5亿
        List<MarketStock> zt = new ArrayList<>(Arrays.asList(
                zt("600001", "旅游及景点", 3, "10.01", 100500, 0, "1000000000"),
                zt("600002", "旅游及景点", 2, "9.98", 92500, 0, "500000000"),
                zt("600003", "旅游及景点", 1, "5.20", 94500, 1, null),
                // 煤炭：两只首板，其中一只一字；各 2 亿封单
                zt("601001", "煤炭", 1, "10.00", 93000, 0, "200000000"),
                zt("601002", "煤炭", 1, "3.10", 101500, 0, "200000000"),
                // 无行业行：进总数与全市场 H=4，但不进排名
                zt("609999", null, 4, "10.02", 93000, 0, "100000000")
        ));
        // 旅游：一只普通炸板 + 一只大面
        List<MarketStock> bomb = Arrays.asList(
                zb("600004", "旅游及景点", 0),
                zb("600005", "旅游及景点", 1));

        IntradayCoreVO vo = service.aggregate(D, zt, bomb, "strength");

        assertTrue(vo.isAvailable());
        assertEquals(6, vo.getZtTotal());
        assertEquals(2, vo.getZbTotal());
        assertEquals(4, vo.getGlobalMaxBoard());
        assertEquals(2, vo.getSectors().size());

        IntradayCoreVO.Sector tour = vo.getSectors().get(0);
        assertEquals("旅游及景点", tour.getIndustry());
        assertEquals(3, tour.getLbCount());
        assertEquals(3, tour.getMaxBoard());
        assertEquals(0, new BigDecimal("1500000000.00").compareTo(tour.getSealSum()));
        assertEquals(1, tour.getYiziCnt());
        assertEquals(1, tour.getReopenCnt());
        assertEquals(2, tour.getZbCnt());
        assertEquals(1, tour.getBigLossCnt());
        assertEquals(3, tour.getTierCnt());
        assertEquals(Arrays.asList(3, 2, 1), tour.getTiers());
        assertEquals("600001", tour.getLeaderCode());
        assertEquals(3, tour.getLeaderBoard());
        assertEquals(10, tour.getPenalty());
        // 25+18.75+20+4.9995+15-10 = 73.7495 → 73.75
        assertEquals(73.75, tour.getStrength().doubleValue(), 0.001);

        IntradayCoreVO.Sector coal = vo.getSectors().get(1);
        assertEquals("煤炭", coal.getIndustry());
        assertEquals(2, coal.getLbCount());
        assertEquals(1, coal.getYiziCnt());
        assertEquals(0, coal.getReopenCnt());
        assertEquals(0, coal.getBigLossCnt());
        // 16.6675+6.25+5.333+7.5+15 = 50.7505 → 50.75
        assertEquals(50.75, coal.getStrength().doubleValue(), 0.01);
    }

    @Test
    void aggregate_无涨停池_available为假不产板块() {
        IntradayCoreVO vo = service.aggregate(D,
                new ArrayList<MarketStock>(),
                Arrays.asList(zb("600001", "旅游及景点", 1)), "strength");
        assertFalse(vo.isAvailable());
        assertTrue(vo.getSectors().isEmpty());
        assertEquals(0, vo.getGlobalMaxBoard());
    }

    @Test
    void aggregate_全首板板块_梯队满分() {
        List<MarketStock> zt = Arrays.asList(
                zt("600001", "煤炭", 1, "10.00", 100000, 0, "100000000"),
                zt("600002", "煤炭", 1, "10.00", 101500, 0, "200000000"));
        IntradayCoreVO vo = service.aggregate(D, zt, new ArrayList<MarketStock>(), null);

        IntradayCoreVO.Sector coal = vo.getSectors().get(0);
        assertEquals(100.0, coal.getTierScore(), 0.001);
        // 家数100 + 高度100 + 封单100 + 一字0 + 梯队100 = 25+25+20+0+15 = 85
        assertEquals(85.00, coal.getStrength().doubleValue(), 0.001);
        assertEquals(0, coal.getYiziCnt());
    }

    @Test
    void yizi_形态边界_与StockPatterns同口径() {
        List<MarketStock> zt = Arrays.asList(
                zt("1", "A", 1, "10", 93000, 0, "1"),   // 一字（含 09:30:00 边界）
                zt("2", "A", 1, "10", 92500, 0, "1"),   // 一字（集合竞价）
                zt("3", "A", 1, "10", 93000, 1, "1"),   // T字，不算一字但算回封
                zt("4", "A", 1, "10", 93001, 0, "1"),   // 换手板
                zt("5", "A", 1, "10", null, 0, "1")     // fbt 历史缺失=未知，不算一字
        );
        IntradayCoreVO vo = service.aggregate(D, zt, new ArrayList<MarketStock>(), "strength");
        IntradayCoreVO.Sector a = vo.getSectors().get(0);
        assertEquals(2, a.getYiziCnt());
        assertEquals(1, a.getReopenCnt());
    }

    @Test
    void leader_同板取涨幅最大() {
        List<MarketStock> zt = Arrays.asList(
                zt("600001", "旅游及景点", 2, "3.00", 100000, 0, "1"),
                zt("600002", "旅游及景点", 2, "9.99", 100000, 0, "1"));
        IntradayCoreVO vo = service.aggregate(D, zt, new ArrayList<MarketStock>(), "strength");
        assertEquals("600002", vo.getSectors().get(0).getLeaderCode());
    }

    @Test
    void sort_按家数排序_未知排序键退化为强度() {
        // 强度五因子契约（家数25/高度25/封单20/一字15/梯队15）下，让两条排序路径的赢家真实不同：
        //  旅游：2 只（3板一字封单5000 + 1板换手封单1000）→ 高度/封单/梯队占优，强度 84.17
        //  煤炭：3 只首板换手，各封单100 → 只在家数上占优，强度 49.33
        // 原 fixture 煤炭封单是旅游的20倍且两只一字，按契约强度本就煤炭赢，与"旅游强度第一"的预期矛盾。
        List<MarketStock> zt = Arrays.asList(
                zt("1", "旅游及景点", 3, "10", 93000, 0, "5000"),
                zt("4", "旅游及景点", 1, "10", 101500, 0, "1000"),
                zt("2", "煤炭", 1, "10", 101500, 0, "100"),
                zt("3", "煤炭", 1, "10", 101500, 0, "100"),
                zt("5", "煤炭", 1, "10", 101500, 0, "100"));
        IntradayCoreVO byStrength = service.aggregate(D, zt, new ArrayList<MarketStock>(), "strength");
        assertEquals("旅游及景点", byStrength.getSectors().get(0).getIndustry());
        assertEquals(84.17, byStrength.getSectors().get(0).getStrength().doubleValue(), 0.01);
        assertEquals(49.33, byStrength.getSectors().get(1).getStrength().doubleValue(), 0.01);
        // 家数排序：煤炭 3 家 > 旅游 2 家
        assertEquals("煤炭",
                service.aggregate(D, zt, new ArrayList<MarketStock>(), "lb").getSectors().get(0).getIndustry());
        // 未知键不报错，退化为强度排序（旅游仍是强度第一，与 lb 排序不同，证明两条路径独立）
        assertEquals("旅游及景点",
                service.aggregate(D, zt, new ArrayList<MarketStock>(), "whatever").getSectors().get(0).getIndustry());
    }

    @Test
    void zbOnly行业不进排名() {
        List<MarketStock> zt = Arrays.asList(zt("1", "旅游及景点", 1, "10", 93000, 0, "1"));
        List<MarketStock> zb = Arrays.asList(zb("2", "煤炭", 1));
        IntradayCoreVO vo = service.aggregate(D, zt, zb, "strength");
        // 炸板行仍计入全市场炸板总数，但只有 ZB 没有 ZT 的煤炭不产生板块排名
        assertEquals(1, vo.getZbTotal());
        assertEquals(1, vo.getSectors().size());
        assertEquals("旅游及景点", vo.getSectors().get(0).getIndustry());
        assertEquals(0, vo.getSectors().get(0).getZbCnt());
        // 旅游有 1 只涨停股：addZT 每只 ZT 都参与板块龙头选取，leader 必然落在它身上
        assertEquals("1", vo.getSectors().get(0).getLeaderCode());
        assertEquals("1", vo.getSectors().get(0).getLeaderName());
    }
}
