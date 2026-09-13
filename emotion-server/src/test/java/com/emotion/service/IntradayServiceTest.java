package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.emotion.entity.MarketStock;
import com.emotion.entity.Theme;
import com.emotion.entity.ThemeStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.StockConceptMapper;
import com.emotion.mapper.ThemeMapper;
import com.emotion.mapper.ThemeStockMapper;
import com.emotion.vo.IntradayVO;

/**
 * {@link IntradayService} 单测：跨行业题材归并、is_primary 主题材去重、辅题材不计、
 * 强度算分与持续天数。全部用 Mock mapper——聚合与回填不连真库（回填/绑定是写库路径，这里只测纯逻辑与聚合）。
 */
class IntradayServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 11);

    private MarketStockMapper msMapper;
    private ThemeMapper themeMapper;
    private ThemeStockMapper tsMapper;
    private StockConceptMapper scMapper;
    private IntradayService service;

    private static MarketStock stock(String code, String name, String industry, int board,
                                     BigDecimal seal, Integer firstSeal, int bigLoss) {
        MarketStock s = new MarketStock();
        s.setTradeDate(D);
        s.setCode(code);
        s.setName(name);
        s.setIndustry(industry);
        s.setConsecutive(board);
        s.setChangePct(new BigDecimal("10.00"));
        s.setSealAmount(seal);
        s.setFirstSealTime(firstSeal);
        s.setBreakCount(0);
        s.setBigLoss(bigLoss);
        return s;
    }

    private static ThemeStock bind(Long themeId, String code, String industry, int primary) {
        ThemeStock ts = new ThemeStock();
        ts.setUserId(1L);
        ts.setThemeId(themeId);
        ts.setTradeDate(D);
        ts.setCode(code);
        ts.setName(code);
        ts.setIndustry(industry);
        ts.setIsPrimary(primary);
        ts.setSource("MANUAL");
        return ts;
    }

    @BeforeEach
    void setUp() {
        msMapper = Mockito.mock(MarketStockMapper.class);
        themeMapper = Mockito.mock(ThemeMapper.class);
        tsMapper = Mockito.mock(ThemeStockMapper.class);
        scMapper = Mockito.mock(StockConceptMapper.class);
        service = new IntradayService(themeMapper, tsMapper, msMapper, scMapper);
    }

    /** 跨行业题材：一只票可归多个题材，主题材去重计数；辅题材不计数不占未归类。 */
    @Test
    void aggregate_crossIndustryTheme_dedupPrimary_ignoreAux() {
        // 全市场 3 只涨停：000001 属元件，另 000002/000003 属通用/自动化（都被归进"机器人"概念）
        List<MarketStock> zt = new ArrayList<>();
        zt.add(stock("000001", "A", "元件", 2, new BigDecimal("100"), 90000, 0));
        zt.add(stock("000002", "B", "通用设备", 3, new BigDecimal("50"), 100000, 1));
        zt.add(stock("000003", "C", "自动化", 1, new BigDecimal("10"), null, 0));
        when(msMapper.selectList(any())).thenReturn(zt);

        List<Theme> themes = new ArrayList<>();
        Theme tYJ = new Theme();
        tYJ.setId(1L); tYJ.setName("元件"); tYJ.setCatalystHardness(4); tYJ.setStatus("扩散"); tYJ.setIsMainLine(1);
        Theme tJQR = new Theme();
        tJQR.setId(2L); tJQR.setName("机器人"); tJQR.setCatalystHardness(3); tJQR.setStatus("萌芽"); tJQR.setIsMainLine(0);
        themes.add(tYJ); themes.add(tJQR);
        when(themeMapper.selectList(any())).thenReturn(themes);

        List<ThemeStock> today = new ArrayList<>();
        // 000001 同时是"元件"主题材 和 "机器人"主题材（一票多题材）
        today.add(bind(1L, "000001", "元件", 1));
        today.add(bind(2L, "000001", "元件", 1));
        today.add(bind(2L, "000002", "通用设备", 1));
        today.add(bind(2L, "000003", "自动化", 1));
        today.add(bind(2L, "000004", "汽车零部", 0)); // 辅题材：不计数
        when(tsMapper.selectList(any())).thenReturn(today); // 当日 + 窗口都被下面复用同一份

        IntradayVO vo = service.aggregate(1L, D);

        assertEquals(3, vo.getTotalZt(), "全市场涨停 3 只");
        assertEquals(3, vo.getAssigned(), "000004 是辅题材不计入，000001 多题材也只算一次");
        assertEquals(0, vo.getUnassigned());

        assertEquals(2, vo.getThemes().size());
        IntradayVO.ThemeRow jqr = vo.getThemes().get(0); // 机器人 zt3/最高3板 应排第一
        assertEquals("机器人", jqr.getName());
        assertEquals(3, jqr.getZtCount(), "辅题材 000004 不计数");
        assertEquals(3, jqr.getMaxBoard());
        assertEquals(3, jqr.getIndustries().size(), "机器人横跨元件+通用设备+自动化");
        assertTrue(jqr.getIndustries().contains("通用设备"));
        assertTrue(jqr.getIndustries().contains("自动化"));
        assertEquals("3,2,1", jqr.getTierLevels());
        assertEquals("B", jqr.getLeader().getName(), "最高板 3 板=B");

        IntradayVO.ThemeRow yj = vo.getThemes().get(1);
        assertEquals("元件", yj.getName());
        assertEquals(1, yj.getZtCount(), "元件只算它自己的主题材");
        assertTrue(yj.isMainLine(), "元件被设为主线题材");
        assertTrue(jqr.getStrength() > yj.getStrength(),
                "机器人更强应排前, robot=" + jqr.getStrength() + " yj=" + yj.getStrength());
    }

    /** 持续天数：窗口内该题材每日主题材 ≥1 才算活跃，逐日 +1；断档归零。 */
    @Test
    void continuousDays_walksActiveDays() {
        TreeMap<LocalDate, Integer> daily = new TreeMap<>();
        daily.put(D, 3);
        daily.put(D.minusDays(1), 2);
        daily.put(D.minusDays(2), 1);
        assertEquals(3, IntradayService.continuousDays(daily, D), "连续 3 天活跃");

        daily.put(D.minusDays(3), 0);
        assertEquals(3, IntradayService.continuousDays(daily, D), "第 4 天 0 只=断档，不 +1");
        assertEquals(1, IntradayService.continuousDays(new TreeMap<LocalDate, Integer>() {{
            put(D, 1); put(D.minusDays(1), 0);
        }}, D), "只见一天活跃=1");
    }

    /** 硬度加分：题材特有的催化加分，硬度 >3 抬分；大面扣分封顶 30 分。 */
    @Test
    void strength_hardnessBonus_andBigLossPenalty() {
        double base = IntradayService.calcThemeStrength(5, 3, 100.0, 1, 0, 3, 3, 5, 10, 200.0);
        double withHardness = IntradayService.calcThemeStrength(5, 3, 100.0, 1, 0, 3, 5, 5, 10, 200.0);
        assertTrue(withHardness > base, "硬度 5 比 3 高 4 档×5=+20 分");

        // 大面扣分封顶 30：5 只×10=50 > 30，差值必须恰为 -30
        double small = IntradayService.calcThemeStrength(5, 3, 100.0, 0, 0, 3, 3, 5, 10, 200.0);
        double big = IntradayService.calcThemeStrength(5, 3, 100.0, 0, 5, 3, 3, 5, 10, 200.0);
        assertEquals(-30.0, big - small, 0.001, "大面扣分不超过 30 分");
    }
}