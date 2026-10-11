package com.emotion.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emotion.entity.Strategy;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.StrategyMapper;
import com.emotion.mapper.TradingHolidayMapper;
import com.emotion.scheduler.TaskResult;
import com.emotion.waverider.WaveRiderEngine;

/**
 * 晚间 T+1 回补（{@link WaveRiderT1BackfillTask}）：断言集中在「扫哪几天」与「什么时候不许扫」上。
 *
 * <p>这个任务的存在理由就是一次排班事故：同一件事原先挂在 15:30 的选股任务里，
 * 连续多日「补写 0 行」而没人发现——因为它不报错，只是安静地什么都不写。
 * 所以这里要盯住的不是顺利那条路的行数，而是三条边界：
 * <ol>
 *   <li>三道前置闸（非交易日 / 没有启用策略 / 验证日行情未入库）都必须<b>一个 mapper 都不碰</b>就退出；</li>
 *   <li>扫的日子必须严格是「有行情明细的那几个交易日」，而不是自然日倒减——
 *       国庆那种长假一倒减就全扫到休市日上，看着跑了三天、实际一行也补不出来；</li>
 *   <li>一个策略抛异常不能把另一个策略带走，否则单点故障会静默吃掉整晚的回补。</li>
 * </ol>
 */
class WaveRiderT1BackfillTaskTest {

    /** 2026-10-09 是周五，且它前面正好隔着国庆长假——用来验「跟着明细日走，不跟着自然日走」。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate D1 = LocalDate.of(2026, 10, 8);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 30);
    private static final LocalDate D3 = LocalDate.of(2026, 9, 29);

    private StrategyMapper strategyMapper;
    private MarketStockMapper marketStockMapper;
    private TradingHolidayMapper holidayMapper;
    private WaveRiderEngine engine;
    private WaveRiderT1BackfillTask task;

    @BeforeEach
    void setUp() {
        strategyMapper = mock(StrategyMapper.class);
        marketStockMapper = mock(MarketStockMapper.class);
        holidayMapper = mock(TradingHolidayMapper.class);
        engine = mock(WaveRiderEngine.class);
        task = new WaveRiderT1BackfillTask(strategyMapper, marketStockMapper, holidayMapper, engine);

        when(holidayMapper.listBetween(any(), any())).thenReturn(Collections.emptyList());
        when(strategyMapper.selectList(any())).thenReturn(Collections.singletonList(strategy(1L, "默认策略")));
        when(engine.hasPoolData(any())).thenReturn(true);
        // 明细日链：10-09 → 10-08 → 09-30 → 09-29，中间跨过 10-01..10-07 的休市
        when(marketStockMapper.prevDetailDate(TODAY)).thenReturn(D1);
        when(marketStockMapper.prevDetailDate(D1)).thenReturn(D2);
        when(marketStockMapper.prevDetailDate(D2)).thenReturn(D3);
        when(marketStockMapper.prevDetailDate(D3)).thenReturn(LocalDate.of(2026, 9, 28));
        when(engine.backfillT1(anyLong(), any())).thenReturn(5);
    }

    @Test
    void holidaySkipsWithoutTouchingEngine() {
        when(holidayMapper.listBetween(any(), any())).thenReturn(Collections.singletonList(TODAY));

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        assertTrue(r.getMessage().contains("非交易日"), r.getMessage());
        verify(engine, never()).backfillT1(anyLong(), any());
    }

    @Test
    void weekendSkipsWithoutQueryingHolidays() {
        // 2026-10-10 是周六：周末应当在查休市表之前就短路
        TaskResult r = task.run(LocalDate.of(2026, 10, 10));

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        verify(holidayMapper, never()).listBetween(any(), any());
        verify(engine, never()).backfillT1(anyLong(), any());
    }

    @Test
    void noEnabledStrategySkips() {
        when(strategyMapper.selectList(any())).thenReturn(Collections.emptyList());

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        assertTrue(r.getMessage().contains("没有启用中的策略"), r.getMessage());
        verify(engine, never()).backfillT1(anyLong(), any());
    }

    /**
     * 验证日的三池没入库时明确 skip，而不是扫一遍报「0 行」。
     *
     * <p>这两种结果在 {@code t_scheduler_run} 里长得一样，但含义相反：
     * 「0 行」是上游日线还没发布（等一晚就好），「行情未入库」是当天的数据根本没拉到（要人去管）。
     */
    @Test
    void missingPoolDataSkipsInsteadOfReportingZero() {
        when(engine.hasPoolData(any())).thenReturn(false);

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        assertTrue(r.getMessage().contains("三池数据未入库"), r.getMessage());
        verify(engine, never()).backfillT1(anyLong(), any());
    }

    /** 扫的是明细日链上那三天（跨过国庆），升序，今天本身不在内——它的 D+1 还不存在。 */
    @Test
    void sweepsThreeDetailDaysNotThreeCalendarDays() {
        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        verify(engine).backfillT1(1L, D3);
        verify(engine).backfillT1(1L, D2);
        verify(engine).backfillT1(1L, D1);
        verify(engine, times(3)).backfillT1(anyLong(), any());
        verify(engine, never()).backfillT1(1L, TODAY);
        assertTrue(r.getMessage().contains("共补写 15 行"), r.getMessage());
    }

    @Test
    void everyEnabledStrategyGetsTheFullSweep() {
        when(strategyMapper.selectList(any()))
                .thenReturn(Arrays.asList(strategy(1L, "主升浪"), strategy(2L, "退潮期")));

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        verify(engine, times(6)).backfillT1(anyLong(), any());
        // 每个策略各走一遍那三天，所以 (2L, D1) 只该出现一次
        verify(engine).backfillT1(2L, D1);
        verify(engine).backfillT1(2L, D3);
        assertTrue(r.getMessage().contains("2 个策略 × 3 个交易日"), r.getMessage());
        assertTrue(r.getMessage().contains("退潮期"), r.getMessage());
    }

    /** 单点故障不许静默吃掉整晚：抛异常那天记一笔，其余日子与其余策略照跑，整体仍是 SUCCESS。 */
    @Test
    void oneDayFailingDoesNotStopTheSweep() {
        doThrow(new RuntimeException("日K取数超时")).when(engine).backfillT1(eq(1L), eq(D2));

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        verify(engine, times(3)).backfillT1(anyLong(), any());
        assertTrue(r.getMessage().contains(D2 + "→失败"), r.getMessage());
        assertTrue(r.getMessage().contains(D1 + "→5行"), r.getMessage());
    }

    @Test
    void oneStrategyFailingDoesNotStopTheOther() {
        when(strategyMapper.selectList(any()))
                .thenReturn(Arrays.asList(strategy(1L, "坏的"), strategy(2L, "好的")));
        // 只让 1 号策略炸：用 doThrow 而不是 when(...).thenThrow——
        // 后者要先真调一次 backfillT1 才能拿到桩，而被调的正是这条要抛异常的桩。
        doThrow(new RuntimeException("炸了")).when(engine).backfillT1(eq(1L), any());

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        verify(engine, times(3)).backfillT1(eq(1L), any());
        verify(engine, times(3)).backfillT1(eq(2L), any());
        assertTrue(r.getMessage().contains("好的"), r.getMessage());
        assertTrue(r.getMessage().contains("坏的"), r.getMessage());
    }

    /** 新策略只有两天历史时不该 NPE，也不该硬凑满三天。 */
    @Test
    void shortHistorySweepsOnlyWhatExists() {
        when(marketStockMapper.prevDetailDate(D2)).thenReturn(null);

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        verify(engine, times(2)).backfillT1(anyLong(), any());
        verify(engine).backfillT1(1L, D1);
        verify(engine).backfillT1(1L, D2);
        assertTrue(r.getMessage().contains("× 2 个交易日"), r.getMessage());
    }

    @Test
    void noHistoryAtAllSkips() {
        when(marketStockMapper.prevDetailDate(any())).thenReturn(null);

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        verify(engine, never()).backfillT1(anyLong(), any());
    }

    private static Strategy strategy(Long id, String name) {
        Strategy s = new Strategy();
        s.setId(id);
        s.setName(name);
        s.setEnabled(1);
        return s;
    }
}
