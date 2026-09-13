package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.emotion.entity.MainlineMark;
import com.emotion.mapper.MainlineMarkMapper;
import com.emotion.vo.MainlineVO;

/**
 * {@link MainlineService#promote}/{@link MainlineService#cancel} 写库路径单测：
 * 「升级到主线区」落 t_mainline_mark、取消则删该行业；命中人工标记后 hasMainline 翻转。
 * PrdMetricsService 以 Mock 提供快照（写库与读快照解耦，聚焦 promote/cancel 的持久化语义）。
 */
class MainlineServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 11);

    private MainlineMarkMapper mapper;
    private PrdMetricsService prd;
    private MainlineService service;

    /** 构造一个只填 vo() 用到字段的快照：未确认主线时回落雷达榜首。 */
    private PrdMetricsService.Snapshot snapshot(boolean confirmed, String main, boolean manual) {
        PrdMetricsService.Snapshot s = new PrdMetricsService.Snapshot();
        s.mainIndustry = main;
        s.mainlineConfirmed = confirmed;
        s.manuallyMarked = manual;
        s.radarTopIndustry = "元件";
        s.ztTotal = 8;
        s.mainZt = main == null ? 0 : 8;
        s.persistenceDays = confirmed ? 3 : 1;
        s.zhongJun = new java.util.ArrayList<>();
        s.fanBao = new java.util.ArrayList<>();
        s.rotationSignals = new java.util.ArrayList<>();
        return s;
    }

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(MainlineMarkMapper.class);
        prd = Mockito.mock(PrdMetricsService.class);
        service = new MainlineService(prd, mapper);
    }

    /** 升级：先删旧标记（幂等）再插入该行业人工标记；返回的快照命中人工标记 → hasMainline=true。 */
    @Test
    void promote_persistsMark_andFlipsHasMainline() {
        when(prd.snapshot(eq(1L), eq(D))).thenReturn(snapshot(true, "元件", true));

        MainlineVO vo = service.promote(1L, D, "元件");

        // DELETE before INSERT（幂等）
        verify(mapper).delete(any());
        verify(mapper).insert(Mockito.argThat((MainlineMark m) ->
                m.getIndustry() == null ? false
                        : (m.getIndustry().equals("元件") && m.getUserId() == 1L
                           && D.equals(m.getTradeDate()) && m.getManual() == 1)));
        assertTrue(vo.getHasMainline(), "升级后 hasMainline 应为 true");
        assertTrue(vo.getManuallyMarked(), "应标人工主线");
        assertEquals("元件", vo.getMainIndustry());
    }

    /** 取消升级：删该日该行业的人工标记；读回未确认主线快照 → hasMainline=false、非人工。 */
    @Test
    void cancel_removesMark_andFlipsHasMainline() {
        when(prd.snapshot(eq(1L), eq(D))).thenReturn(snapshot(false, "元件", false));

        MainlineVO vo = service.cancel(1L, D, "元件");

        verify(mapper).delete(any());
        assertFalse(vo.getHasMainline(), "取消后 hasMainline 应为 false");
        assertFalse(vo.getManuallyMarked(), "取消后不应再是人工主线");
    }

    /** 升级传空行业名：直接抛非法参数，不落库。 */
    @Test
    void promote_blankIndustry_rejects() {
        try {
            service.promote(1L, D, "  ");
            org.junit.jupiter.api.Assertions.fail("应为 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Mockito.verify(mapper, Mockito.never()).insert(any());
        }
    }

    /** 升级/取消不落地当天日期的默认场景：date=null 时用系统日（仅验证不抛错、走删除）。 */
    @Test
    void promote_nullDate_usesToday() {
        when(prd.snapshot(eq(1L), any())).thenReturn(snapshot(true, "元件", true));
        MainlineVO vo = service.promote(1L, null, "元件");
        assertEquals("元件", vo.getMainIndustry());
        verify(mapper).delete(any());
    }
}