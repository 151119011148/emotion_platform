package com.emotion.service;

import com.emotion.dto.MarketFields;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.vo.MarketSnapshotVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日复盘一键拉取（T1-T8）编排的两条核心性质：
 * <ul>
 *   <li><b>周末/未来日期直接拒绝</b>——不碰任何上游，T1 即 fail 并落 FAILED；</li>
 *   <li><b>正常交易日 T1-T8 依序各产生一次终结事件</b>，末尾有 done 汇总，并把状态落档 t_review_fetch。</li>
 * </ul>
 */
class ReviewFetchServiceTest {

    private static final LocalDate TRADING = LocalDate.of(2026, 9, 10); // 周四，非周末且晚于今天(09-13)
    private static final LocalDate SUNDAY = LocalDate.of(2026, 9, 13);
    private static final long USER = 1L;

    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final MarketDailyStore marketDailyStore = mock(MarketDailyStore.class);
    private final IndexCloseStore indexCloseStore = mock(IndexCloseStore.class);
    private final PremiumTierStore premiumTierStore = mock(PremiumTierStore.class);
    private final MarketStockMapper marketStockMapper = mock(MarketStockMapper.class);
    private final IndustrySnapshotService industrySnapshotService = mock(IndustrySnapshotService.class);
    private final SurveillanceService surveillanceService = mock(SurveillanceService.class);
    private final DailyRecordService dailyRecordService = mock(DailyRecordService.class);
    private final ReviewFetchStore fetchStore = mock(ReviewFetchStore.class);

    private final ReviewFetchService service = new ReviewFetchService(
            marketDataService, marketDailyStore, indexCloseStore, premiumTierStore,
            marketStockMapper, industrySnapshotService, surveillanceService,
            dailyRecordService, fetchStore, new ObjectMapper());

    private static final class Collector {
        final List<ReviewFetchService.Event> events = new ArrayList<>();
    }

    @Test
    void weekendDateIsRejectedBeforeAnyUpstreamCall() {
        Collector c = new Collector();
        service.runFetch(SUNDAY, USER, c.events::add);

        assertTrue(c.events.stream().anyMatch(e -> "T1".equals(e.task) && "fail".equals(e.status)));
        assertEquals(2, c.events.size(), "周末应只有 T1 的 running+fail 两条事件");
        assertEquals("T1", c.events.get(0).task);
        assertTrue(c.events.stream().allMatch(e -> "T1".equals(e.task)));
        verify(marketDataService, never()).snapshot(any(), anyBoolean());
        verify(fetchStore).save(eq(SUNDAY), eq("FAILED"), any(), any());
    }

    @Test
    void normalTradingDayRunsAllEightTasksThenPersistsPartialStatus() {
        // T1 回补：库里始终有往前天的涨停池 → 不需要真的去拉历史
        when(marketStockMapper.selectCount(any())).thenReturn(10L);

        MarketSnapshotVO snap = new MarketSnapshotVO();
        snap.setTradeDate(TRADING);
        snap.setSnapshotDate(TRADING);
        snap.setFilled(new MarketFields());
        when(marketDataService.snapshot(eq(TRADING), eq(true))).thenReturn(snap);

        when(indexCloseStore.read(TRADING)).thenReturn(java.util.Collections.emptyList());
        when(premiumTierStore.read(TRADING)).thenReturn(null);
        when(industrySnapshotService.replaceForDate(TRADING)).thenReturn(3);
        when(surveillanceService.trackedCodes(TRADING, TRADING)).thenReturn(java.util.Collections.emptyList());
        when(dailyRecordService.recalc(USER, TRADING)).thenReturn(null);

        Collector c = new Collector();
        service.runFetch(TRADING, USER, c.events::add);

        // 每个任务恰好有一次终结态（T1..T8 + done，不含 running 起手）
        Map<String, Long> terminal = countTerminal(c.events);
        for (String task : new String[]{"T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "done"}) {
            assertEquals(1L, terminal.getOrDefault(task, 0L), task + " 应恰有一次终结事件");
        }
        // 每个任务先 running 后终结，顺序没有被搅乱
        assertEquals("T1", c.events.get(0).task);
        assertEquals("running", c.events.get(0).status);

        verify(fetchStore).save(eq(TRADING), any(), any(), any());
        // 因为没有复盘记录与监管源 → 必然 PARTIAL（缺维/缺监管不阻断，正是 PRD 要的"部分失败不阻断"）
        org.mockito.ArgumentCaptor<String> overall = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fetchStore).save(eq(TRADING), overall.capture(), any(), any());
        assertEquals("PARTIAL", overall.getValue());
    }

    private static Map<String, Long> countTerminal(List<ReviewFetchService.Event> events) {
        Map<String, Long> counts = new HashMap<>();
        for (ReviewFetchService.Event e : events) {
            if ("running".equals(e.status)) {
                continue;
            }
            counts.merge(e.task, 1L, Long::sum);
        }
        return counts;
    }
}