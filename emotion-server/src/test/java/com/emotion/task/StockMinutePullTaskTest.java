package com.emotion.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.TradingHolidayMapper;
import com.emotion.market.TencentClient;
import com.emotion.market.TencentClient.MinuteSeries;
import com.emotion.market.TencentClient.MinuteTick;
import com.emotion.scheduler.TaskResult;
import com.emotion.service.IntradayStore;

/**
 * 19:12 分钟序列（{@link StockMinutePullTask}）。
 *
 * <p>腾讯分钟接口只给当天，所以这个任务没有「明天补回来」这回事：漏一晚，那一天的分时在这台
 * 机器上就永久不存在。因此下面断言的重心是<strong>失败必须报 FAILED</strong>——
 * 名单没入库、上游整批不可用这两种情况如果报成 SUCCESS 或 SKIPPED，
 * {@code t_scheduler_run} 里看起来一切正常，缺的那一天要等到某天复盘想看来时才会发现。
 */
class StockMinutePullTaskTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private TencentClient client;
    private IntradayStore store;
    private MarketStockMapper marketStockMapper;
    private TradingHolidayMapper holidayMapper;
    private StockMinutePullTask task;

    @BeforeEach
    void setUp() {
        client = mock(TencentClient.class);
        store = mock(IntradayStore.class);
        marketStockMapper = mock(MarketStockMapper.class);
        holidayMapper = mock(TradingHolidayMapper.class);
        task = new StockMinutePullTask(client, store, marketStockMapper, holidayMapper);

        when(holidayMapper.listBetween(any(), any())).thenReturn(Collections.emptyList());
        when(marketStockMapper.listHotCodes(TODAY)).thenReturn(Arrays.asList("002909", "600000"));
        when(store.saveMinutes(anyString(), any(), anyList())).thenReturn(267);
        when(client.minuteSeries(anyString())).thenReturn(series(TODAY, 267));
    }

    @Test
    void weekendSkipsBeforeTouchingAnySource() {
        TaskResult r = task.run(LocalDate.of(2026, 10, 31));   // 周六

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        verify(holidayMapper, never()).listBetween(any(), any());
        verify(client, never()).minuteSeries(anyString());
    }

    @Test
    void holidaySkips() {
        when(holidayMapper.listBetween(any(), any())).thenReturn(Collections.singletonList(TODAY));

        assertEquals(TaskResult.SKIPPED, task.run(TODAY).getStatus());
        verify(client, never()).minuteSeries(anyString());
    }

    /**
     * 当天三池明细还没入库 → FAILED，不是 skip。
     *
     * <p>名单来自 19:00 那趟行情拉取。它没跑成，这一晚就抓不到任何一只票的分时，而第二天
     * 接口只给新的那一天——这不是「这次不用做」，是「今天不做就永远没了」。
     */
    @Test
    void missingPoolDetailFailsLoudly() {
        when(marketStockMapper.listHotCodes(TODAY)).thenReturn(Collections.emptyList());

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.FAILED, r.getStatus());
        assertTrue(r.getMessage().contains("永久缺失"), r.getMessage());
        verify(client, never()).minuteSeries(anyString());
    }

    @Test
    void pullsEveryStockInTheRosterOnceAndSumsTheRows() {
        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        verify(client).minuteSeries("sz002909");
        verify(client).minuteSeries("sh600000");
        verify(client, times(2)).minuteSeries(anyString());
        verify(store).saveMinutes(eq("002909"), eq(TODAY), anyList());
        verify(store).saveMinutes(eq("600000"), eq(TODAY), anyList());
        assertTrue(r.getMessage().contains("2 只 / 534 行"), r.getMessage());
    }

    /** 单只票空响应只点名、不中断：一次抽风丢一只有的分时，比整趟停摆丢掉全部好。 */
    @Test
    void oneEmptyStockIsNamedAndTheRestStillGetPulled() {
        when(client.minuteSeries("sz002909")).thenReturn(MinuteSeries.empty());

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        assertTrue(r.getMessage().contains("002909"), r.getMessage());
        assertTrue(r.getMessage().contains("1 只 / 267 行"), r.getMessage());
        verify(store, never()).saveMinutes(eq("002909"), any(), anyList());
        verify(client).minuteSeries("sh600000");
    }

    /**
     * 上游给的是别的日期的序列，就不落库。
     *
     * <p>这一条防的是最坏的一种脏数据：把前一天的分时按今天的日期写进去，
     * 两天的曲线叠成一条看起来正常的假分时。缺一只是缺，画错是错。
     */
    @Test
    void refusesASeriesFromAnotherDay() {
        when(client.minuteSeries("sz002909")).thenReturn(series(LocalDate.of(2026, 10, 27), 267));

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        assertTrue(r.getMessage().contains("序列日期2026-10-27"), r.getMessage());
        verify(store, never()).saveMinutes(eq("002909"), any(), anyList());
    }

    /** date 为 null（上游没给日期列）时照写：序列本身是好的，日期由落库那一刻的行自己带。 */
    @Test
    void stillWritesWhenTheSeriesCarriesNoDate() {
        when(client.minuteSeries("sz002909")).thenReturn(series(null, 3));
        when(store.saveMinutes(eq("002909"), any(), anyList())).thenReturn(3);

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        verify(store).saveMinutes("002909", TODAY, ticks(3));
    }

    /** 一只票抛异常不能把剩下的几十只一起带走——过了今晚就没有第二次机会。 */
    @Test
    void oneExceptionDoesNotStopTheLoop() {
        doThrow(new RuntimeException("连接超时")).when(client).minuteSeries("sz002909");

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        assertTrue(r.getMessage().contains("002909(异常)"), r.getMessage());
        verify(client).minuteSeries("sh600000");
        verify(store).saveMinutes(eq("600000"), eq(TODAY), anyList());
    }

    /** 全部没取到 = 这一次上游不可用，报 FAILED，别让缺的一天悄悄过去。 */
    @Test
    void allStocksEmptyFails() {
        when(client.minuteSeries(anyString())).thenReturn(MinuteSeries.empty());

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.FAILED, r.getStatus());
        assertTrue(r.getMessage().contains("一只都没落库"), r.getMessage());
        verify(store, never()).saveMinutes(anyString(), any(), anyList());
    }

    /** 存了 0 行（比如整段的 tick 都没有分钟位）也算这只票没拿到。 */
    @Test
    void zeroRowsWrittenCountsAsMissing() {
        when(store.saveMinutes(eq("002909"), any(), anyList())).thenReturn(0);

        TaskResult r = task.run(TODAY);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        assertTrue(r.getMessage().contains("缺 1 只：002909"), r.getMessage());
    }

    private static MinuteSeries series(LocalDate date, int size) {
        return new MinuteSeries(date, ticks(size));
    }

    private static List<MinuteTick> ticks(int size) {
        List<MinuteTick> list = new ArrayList<MinuteTick>();
        for (int i = 0; i < size; i++) {
            list.add(new MinuteTick(930 + i, new BigDecimal("5.54"), (long) (i + 1), new BigDecimal("1000.00")));
        }
        return list;
    }
}
