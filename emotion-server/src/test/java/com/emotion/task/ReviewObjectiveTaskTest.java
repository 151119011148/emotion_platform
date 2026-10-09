package com.emotion.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.TradingHolidayMapper;
import com.emotion.market.PoolCounts;
import com.emotion.scheduler.TaskResult;
import com.emotion.service.DailyRecordService;
import com.emotion.service.ReviewFetchService;

/**
 * 定时录入复盘客观数据（{@link ReviewObjectiveTask}）：断言集中在「什么时候<b>不许</b>写」上。
 *
 * <p>这个任务每晚自动往他的交易库插行，所以真正要盯住的不是顺利那条路，而是三道闸：
 * <ol>
 *   <li>非交易日不写；</li>
 *   <li>拉取编排整体失败时不建行——原始数据没落地，建出来就是个空壳；</li>
 *   <li>当日三池明细为空时不建行——空行在页面上和「这天什么都没发生」无法区分，比不写更坏。</li>
 * </ol>
 * 另外两条口径：一个交易日全局只有那一行 {@code t_daily_record}（复盘不再按账号分行，
 * 所以任务也不会有「给每个复盘人各跑一遍编排」这一步），且永远不走 {@code createOrUpdate}
 * （那条路的 {@code copyFields} 会把 {@code stage_overridden} 洗成 0）。
 */
class ReviewObjectiveTaskTest {

    /** 2026-09-29 是周二：避开"跑测试那天恰好是周末"导致 isTradingDay 直接短路。 */
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    private TradingHolidayMapper holidayMapper;
    private MarketStockMapper marketStockMapper;
    private ReviewFetchService fetchService;
    private DailyRecordService recordService;
    private ReviewObjectiveTask task;

    @BeforeEach
    void setUp() {
        holidayMapper = mock(TradingHolidayMapper.class);
        marketStockMapper = mock(MarketStockMapper.class);
        fetchService = mock(ReviewFetchService.class);
        recordService = mock(DailyRecordService.class);
        task = new ReviewObjectiveTask(holidayMapper, marketStockMapper, fetchService, recordService);

        when(holidayMapper.listBetween(any(), any())).thenReturn(Collections.emptyList());
        when(marketStockMapper.countPools(any())).thenReturn(pools(57, 12));
        when(recordService.ensureObjectiveRecord(any())).thenReturn(true);
        emitFetch("ok");
    }

    @Test
    void holidaySkipsWithoutTouchingAnything() {
        when(holidayMapper.listBetween(any(), any())).thenReturn(Collections.singletonList(DAY));

        TaskResult r = task.run(DAY);

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        assertTrue(r.getMessage().contains("非交易日"), r.getMessage());
        verify(fetchService, never()).runFetch(any(), any());
        verify(recordService, never()).ensureObjectiveRecord(any());
    }

    @Test
    void orchestrationFailureDoesNotCreateRecordRow() {
        emitFetch("fail");

        TaskResult r = task.run(DAY);

        assertEquals(TaskResult.FAILED, r.getStatus());
        assertTrue(r.getMessage().contains("不建复盘记录行"), r.getMessage());
        verify(recordService, never()).ensureObjectiveRecord(any());
    }

    @Test
    void emptyPoolDetailDoesNotCreateRecordRow() {
        when(marketStockMapper.countPools(any())).thenReturn(pools(0, 0));

        TaskResult r = task.run(DAY);

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        assertTrue(r.getMessage().contains("三池明细为空"), r.getMessage());
        verify(recordService, never()).ensureObjectiveRecord(any());
    }

    /** 顺利那条路：编排只跑一次，记录行按当天那一行走，结果串说清新建还是只重算。 */
    @Test
    void runsOrchestrationOnceAndWritesTheDayItsSingleRow() {
        TaskResult r = task.run(DAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        assertTrue(r.getMessage().contains("当天新建一行"), r.getMessage());
        assertTrue(r.getMessage().contains("涨停 57 家"), r.getMessage());
        verify(fetchService, times(1)).runFetch(eq(DAY), any());
        verify(recordService, times(1)).ensureObjectiveRecord(DAY);
    }

    /** 当天已有那一行时不能再报"新建"：他白天手动存过复盘，晚上这趟只重算派生列。 */
    @Test
    void reportsRecalcWhenTheDayAlreadyHasARow() {
        when(recordService.ensureObjectiveRecord(any())).thenReturn(false);

        TaskResult r = task.run(DAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        assertTrue(r.getMessage().contains("只重算派生列"), r.getMessage());
    }

    @Test
    void neverRoutesThroughCreateOrUpdateWhichWouldWipeStageOverride() {
        task.run(DAY);

        // createOrUpdate 的 copyFields else 分支会把 stage_overridden 洗成 0，机器绝不能走那条路
        verify(recordService, never()).createOrUpdate(any(), any());
    }

    @Test
    void recordWriteFailureIsReportedAsFailure() {
        when(recordService.ensureObjectiveRecord(any()))
                .thenThrow(new IllegalStateException("库连接断了"));

        TaskResult r = task.run(DAY);

        assertEquals(TaskResult.FAILED, r.getStatus());
        assertTrue(r.getMessage().contains("记录行写入失败"), r.getMessage());
    }

    // ------------------------------------------------------------------ 夹具

    private static PoolCounts pools(int zt, int zb) {
        PoolCounts p = new PoolCounts();
        p.setZtCount(zt);
        p.setZbCount(zb);
        return p;
    }

    /** 让 mock 的编排往 sink 里吐一串事件片，末尾的 done 决定任务是否认为数据落了地。 */
    private void emitFetch(String doneStatus) {
        doAnswer(inv -> {
            Consumer<ReviewFetchService.Event> sink = inv.getArgument(1);
            List<String[]> steps = Arrays.asList(
                    new String[]{"T1", "ok"}, new String[]{"T2", "fail".equals(doneStatus) ? "fail" : "ok"},
                    new String[]{"T3", "fail".equals(doneStatus) ? "fail" : "ok"},
                    new String[]{"T4", "fail".equals(doneStatus) ? "fail" : "ok"},
                    new String[]{"T5", "ok"}, new String[]{"T6", "ok"}, new String[]{"T7", "warn"},
                    new String[]{"T8", "warn"});
            for (String[] s : steps) {
                sink.accept(new ReviewFetchService.Event(s[0], "running", null, "起手"));
                sink.accept(new ReviewFetchService.Event(s[0], s[1], 1, "结论"));
            }
            sink.accept(new ReviewFetchService.Event("done", doneStatus, null, "编排完成"));
            return null;
        }).when(fetchService).runFetch(any(), any());
    }
}
