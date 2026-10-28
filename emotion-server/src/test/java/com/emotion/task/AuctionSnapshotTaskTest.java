package com.emotion.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.emotion.entity.Anchor;
import com.emotion.entity.StockAuction;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.PositionMapper;
import com.emotion.mapper.TradingHolidayMapper;
import com.emotion.market.TencentClient;
import com.emotion.scheduler.TaskResult;
import com.emotion.service.AnchorService;
import com.emotion.service.IntradayStore;

/**
 * 09:26 竞价快照（{@link AuctionSnapshotTask}）。
 *
 * <p>这个任务全部的风险都集中在<b>时间窗</b>上：竞价量不是上游给的一个字段，而是
 * 「09:30 之前那一刻的累计成交量」，所以晚跑的报文看起来完全正常，含义却已经换成了
 * 开盘后的量——它不会报错，只会安静地写进一批假数据。因此下面第一组用例断言的都是
 * 「出了窗口一个字节都不写」，而不是顺利那条路的行数。
 */
class AuctionSnapshotTaskTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final LocalDate PREV = LocalDate.of(2026, 10, 27);
    private static final LocalTime IN_WINDOW = LocalTime.of(9, 26);

    private TencentClient tencentClient;
    private IntradayStore store;
    private MarketStockMapper marketStockMapper;
    private PositionMapper positionMapper;
    private AnchorService anchorService;
    private TradingHolidayMapper holidayMapper;
    private AuctionSnapshotTask task;

    @BeforeEach
    void setUp() {
        tencentClient = mock(TencentClient.class);
        store = mock(IntradayStore.class);
        marketStockMapper = mock(MarketStockMapper.class);
        positionMapper = mock(PositionMapper.class);
        anchorService = mock(AnchorService.class);
        holidayMapper = mock(TradingHolidayMapper.class);
        task = new AuctionSnapshotTask(tencentClient, store, marketStockMapper,
                positionMapper, anchorService, holidayMapper);

        when(holidayMapper.listBetween(any(), any())).thenReturn(Collections.emptyList());
        when(marketStockMapper.prevDetailDate(TODAY)).thenReturn(PREV);
        when(marketStockMapper.listHotCodes(PREV)).thenReturn(Arrays.asList("002909", "600000"));
        when(anchorService.listInPosition(TODAY)).thenReturn(Collections.emptyList());
        when(positionMapper.listHeldCodesLatest()).thenReturn(Collections.emptyList());
        when(store.saveAuctions(anyList())).thenReturn(2);
    }

    // ---- 时间窗 ----

    @Test
    void weekendSkipsBeforeTouchingAnySource() {
        TaskResult r = task.run(LocalDate.of(2026, 10, 31), IN_WINDOW);   // 周六

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        assertTrue(r.getMessage().contains("非交易日"), r.getMessage());
        verify(holidayMapper, never()).listBetween(any(), any());
        verify(tencentClient, never()).quotes(anyList());
    }

    @Test
    void holidaySkips() {
        when(holidayMapper.listBetween(any(), any())).thenReturn(Collections.singletonList(TODAY));

        assertEquals(TaskResult.SKIPPED, task.run(TODAY, IN_WINDOW).getStatus());
        verify(tencentClient, never()).quotes(anyList());
    }

    /** 09:25 之前累计量还是 0——写进去是一行「竞价 0 手」，那是假数而不是缺数。 */
    @Test
    void refusesToRunBeforeTheAuctionResultIsOut() {
        TaskResult r = task.run(TODAY, LocalTime.of(9, 20));

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        assertTrue(r.getMessage().contains("09:25"), r.getMessage());
        verify(tencentClient, never()).quotes(anyList());
        verify(store, never()).saveAuctions(anyList());
    }

    /**
     * 09:30 整点就停，一刻都不通融。
     *
     * <p>这是本任务唯一真正防住的事故：过了开盘，累计成交量里混进连续竞价的量，
     * 而 {@code volume_hand} 这一列的名字还叫竞价量。晚到的补跑必须 refuse，
     * 这也是 {@code misfire_policy=DO_NOTHING} 的原因——它补不了。
     */
    @Test
    void refusesToRunAfterTheOpen() {
        for (LocalTime late : Arrays.asList(LocalTime.of(9, 30), LocalTime.of(9, 31), LocalTime.of(15, 0))) {
            TaskResult r = task.run(TODAY, late);

            assertEquals(TaskResult.SKIPPED, r.getStatus(), late.toString());
            assertTrue(r.getMessage().contains("已开盘"), late + " -> " + r.getMessage());
        }
        verify(tencentClient, never()).quotes(anyList());
        verify(store, never()).saveAuctions(anyList());
    }

    @Test
    void runsAtTheLastMinuteOfTheWindow() {
        stubQuotes(quote("sz002909", "002909", "集泰股份", "5.54"));

        assertEquals(TaskResult.SUCCESS, task.run(TODAY, LocalTime.of(9, 29, 59)).getStatus());
    }

    // ---- 名单 ----

    /** 昨日两池 ＋ 在位阵眼 ＋ 最近持仓，合成一份去重名单；重复代码会让同一次批量请求里出现两次同一只票。 */
    @Test
    @SuppressWarnings("unchecked")
    void rosterIsYesterdayPoolsPlusAnchorsPlusHoldingsDeduped() {
        when(marketStockMapper.listHotCodes(PREV)).thenReturn(Arrays.asList("002909", "600000", "002909"));
        when(anchorService.listInPosition(TODAY)).thenReturn(Collections.singletonList(anchor("301199")));
        when(positionMapper.listHeldCodesLatest()).thenReturn(Arrays.asList("600000", "605577"));
        stubQuotes();

        task.run(TODAY, IN_WINDOW);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(tencentClient).quotes(captor.capture());

        List<String> symbols = captor.getValue();
        // 002909 在昨日池里出现两次、600000 既在池里又在持仓里，去重后应当各只有一次
        assertEquals(4, symbols.size());
        assertEquals(new java.util.HashSet<String>(
                        Arrays.asList("sz002909", "sh600000", "sz301199", "sh605577")),
                new java.util.HashSet<String>(symbols));
    }

    /** 昨日没有明细、又没有阵眼和持仓：没有名单就没有快照，但这不是故障。 */
    @Test
    void emptyRosterSkipsWithoutCallingUpstream() {
        when(marketStockMapper.prevDetailDate(TODAY)).thenReturn(null);

        TaskResult r = task.run(TODAY, IN_WINDOW);

        assertEquals(TaskResult.SKIPPED, r.getStatus());
        assertTrue(r.getMessage().contains("没有名单"), r.getMessage());
        verify(tencentClient, never()).quotes(anyList());
    }

    // ---- 写库与告警 ----

    /**
     * 整批空响应要报 FAILED。
     *
     * <p>这是唯一会让他早上看见红条的分支，而红条是有意义的：限流导致一条有效报价都没拿到时，
     * 这一天的竞价就永久没了（9:30 之后不能重抓），必须当场知道。报 SUCCESS 就等于把这件事藏起来。
     */
    @Test
    void allEmptyQuotesFailsWithoutWriting() {
        when(tencentClient.quotes(anyList())).thenReturn(Collections.emptyMap());

        TaskResult r = task.run(TODAY, IN_WINDOW);

        assertEquals(TaskResult.FAILED, r.getStatus());
        assertTrue(r.getMessage().contains("未写库"), r.getMessage());
        verify(store, never()).saveAuctions(anyList());
    }

    /** 只有一行没有现价（比如退市/停牌）时其余照写，缺的数目如实报出来。 */
    @Test
    void dropsRowsWithoutAPriceButKeepsTheRest() {
        Map<String, TencentClient.StockQuote> quotes = new LinkedHashMap<>();
        quotes.put("sz002909", quote("sz002909", "002909", "集泰股份", "5.54"));
        quotes.put("sh600000", quote("sh600000", "600000", "浦发银行", null));
        when(tencentClient.quotes(anyList())).thenReturn(quotes);
        when(store.saveAuctions(anyList())).thenReturn(1);

        TaskResult r = task.run(TODAY, IN_WINDOW);

        assertEquals(TaskResult.SUCCESS, r.getStatus());
        assertTrue(r.getMessage().contains("已落库 1 只"), r.getMessage());
        assertTrue(r.getMessage().contains("缺 1 只"), r.getMessage());

        ArgumentCaptor<List<StockAuction>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).saveAuctions(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("002909", captor.getValue().get(0).getCode());
        assertEquals(TODAY.atTime(IN_WINDOW), captor.getValue().get(0).getCapturedAt());
    }

    private void stubQuotes(TencentClient.StockQuote... extra) {
        Map<String, TencentClient.StockQuote> quotes = new LinkedHashMap<>();
        quotes.put("sz002909", quote("sz002909", "002909", "集泰股份", "5.54"));
        quotes.put("sh600000", quote("sh600000", "600000", "浦发银行", "9.43"));
        for (TencentClient.StockQuote q : extra) {
            quotes.put(q.getSymbol(), q);
        }
        when(tencentClient.quotes(anyList())).thenReturn(quotes);
    }

    private static TencentClient.StockQuote quote(String symbol, String code, String name, String last) {
        TencentClient.StockQuote q = new TencentClient.StockQuote();
        q.setSymbol(symbol);
        q.setCode(code);
        q.setName(name);
        q.setLastPrice(last == null ? null : new BigDecimal(last));
        return q;
    }

    private static Anchor anchor(String code) {
        Anchor a = new Anchor();
        a.setStockCode(code);
        // 角色在这个任务里不参与判断，给个常量值就行，别让测试去猜业务
        a.setRole(AnchorService.ROLE_ZONG);
        return a;
    }
}
