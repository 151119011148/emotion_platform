package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.emotion.entity.StockAuction;
import com.emotion.entity.StockMinute;
import com.emotion.mapper.StockAuctionMapper;
import com.emotion.mapper.StockMinuteMapper;
import com.emotion.market.TencentClient.MinuteTick;

/**
 * 盘中数据落库口。这里没有一条业务判据，所以测试全部盯在「拼出来的行对不对」和
 * 「什么情况下一个字都不该写」上——写错列名或静默丢字段，要到第二天早上才会被发现。
 */
class IntradayStoreTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final LocalDateTime CAPTURED = LocalDateTime.of(2026, 10, 28, 9, 26, 3);

    private StockMinuteMapper minuteMapper;
    private StockAuctionMapper auctionMapper;
    private IntradayStore store;

    @BeforeEach
    void setUp() {
        minuteMapper = mock(StockMinuteMapper.class);
        auctionMapper = mock(StockAuctionMapper.class);
        store = new IntradayStore(minuteMapper, auctionMapper);
        when(minuteMapper.upsertBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        when(auctionMapper.upsertBatch(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
    }

    @Test
    void writesNothingForAnEmptyTickList() {
        assertEquals(0, store.saveMinutes("002909", DAY, null));
        assertEquals(0, store.saveMinutes("002909", DAY, Collections.emptyList()));
        assertEquals(0, store.saveMinutes(null, DAY, Collections.singletonList(tick(930, "5.54", 254L, "140716.00"))));
        assertEquals(0, store.saveMinutes("002909", null,
                Collections.singletonList(tick(930, "5.54", 254L, "140716.00"))));
        verify(minuteMapper, never()).upsertBatch(anyList());
    }

    /** 列对位：累计量落在 cum_volume_hand、累计额落在 cum_amount_yuan，一次一票一整天。 */
    @Test
    @SuppressWarnings("unchecked")
    void assemblesOneRowPerMinuteAndKeepsCumulativeColumns() {
        int written = store.saveMinutes("002909", DAY, Arrays.asList(
                tick(930, "5.54", 254L, "140716.00"),
                tick(931, "5.52", 2901L, "1611405.00")));

        assertEquals(2, written);
        ArgumentCaptor<List<StockMinute>> captor = ArgumentCaptor.forClass(List.class);
        verify(minuteMapper).upsertBatch(captor.capture());

        List<StockMinute> rows = captor.getValue();
        assertEquals(2, rows.size());
        StockMinute first = rows.get(0);
        assertEquals("002909", first.getCode());
        assertEquals(DAY, first.getTradeDate());
        assertEquals(Integer.valueOf(930), first.getMinute());
        assertEquals(0, new BigDecimal("5.54").compareTo(first.getPrice()));
        assertEquals(Long.valueOf(254L), first.getCumVolumeHand());
        assertEquals(0, new BigDecimal("140716.00").compareTo(first.getCumAmountYuan()));
        assertEquals(Integer.valueOf(931), rows.get(1).getMinute());
    }

    @Test
    void skipsATickWithoutAMinuteRatherThanWritingAnUnaddressableRow() {
        // 没有 HHMM 就没有唯一键，写进去既查不到也删不掉，只能丢
        List<MinuteTick> ticks = new ArrayList<MinuteTick>();
        ticks.add(new MinuteTick(null, new BigDecimal("5.54"), 1L, null));
        ticks.add(tick(930, "5.54", 254L, "140716.00"));

        assertEquals(1, store.saveMinutes("002909", DAY, ticks));
    }

    @Test
    void emptyAuctionBatchDoesNotHitTheDatabase() {
        assertEquals(0, store.saveAuctions(null));
        assertEquals(0, store.saveAuctions(Collections.emptyList()));
        verify(auctionMapper, never()).upsertBatch(anyList());
    }

    /** 竞价行的每一个位子都要对上实体，字段一多最容易错的就是这里。 */
    @Test
    void mapsAQuoteOntoAnAuctionRow() {
        TencentClientQuoteStub quote = new TencentClientQuoteStub();
        StockAuction row = IntradayStore.toAuction(quote.quote(), DAY, CAPTURED);

        assertEquals("002909", row.getCode());
        assertEquals("集泰股份", row.getName());
        assertEquals(DAY, row.getTradeDate());
        assertEquals(0, new BigDecimal("5.54").compareTo(row.getAuctionPrice()));
        assertEquals(0, new BigDecimal("5.50").compareTo(row.getPrevClose()));
        assertEquals(Long.valueOf(1200L), row.getVolumeHand());
        assertEquals(0, new BigDecimal("0.66").compareTo(row.getAmountWan()));
        assertEquals(0, new BigDecimal("5.54").compareTo(row.getBid1Price()));
        assertEquals(Long.valueOf(800L), row.getBid1Hand());
        assertEquals(0, new BigDecimal("5.55").compareTo(row.getAsk1Price()));
        assertEquals(Long.valueOf(0L), row.getAsk1Hand());
        assertEquals(0, new BigDecimal("6.05").compareTo(row.getLimitUpPrice()));
        assertEquals(CAPTURED, row.getCapturedAt());
        assertEquals("1~集泰股份~002909~5.54", row.getSnapshotRaw());
    }

    /**
     * 没有现价就不是一个报价。
     *
     * <p>限流、空响应、退市都会给出一行除了代码什么都没有的东西。这种行一旦落库，
     * 页面上读出来就是「今天竞价 0 元成交」——比少一行有害得多，所以在门口拦掉。
     */
    @Test
    void refusesRowsWithoutAPriceOrACode() {
        assertNull(IntradayStore.toAuction(null, DAY, CAPTURED));

        com.emotion.market.TencentClient.StockQuote noPrice = new com.emotion.market.TencentClient.StockQuote();
        noPrice.setCode("002909");
        assertNull(IntradayStore.toAuction(noPrice, DAY, CAPTURED));

        com.emotion.market.TencentClient.StockQuote noCode = new com.emotion.market.TencentClient.StockQuote();
        noCode.setLastPrice(new BigDecimal("5.54"));
        assertNull(IntradayStore.toAuction(noCode, DAY, CAPTURED));
    }

    /** 名称缺就落空串：那列 NOT NULL，给 null 会让一整批一起失败。 */
    @Test
    void keepsAnAbsentNameAsEmptyStringNotAsNull() {
        com.emotion.market.TencentClient.StockQuote quote = new com.emotion.market.TencentClient.StockQuote();
        quote.setCode("002909");
        quote.setLastPrice(new BigDecimal("5.54"));

        StockAuction row = IntradayStore.toAuction(quote, DAY, CAPTURED);
        assertEquals("", row.getName());
        assertNull(row.getBid1Price());
        assertNull(row.getSnapshotRaw());
    }

    /** 原文列是 VARCHAR(1000)：超长要截断，不能让一段畸形长行把整批快照顶掉。 */
    @Test
    void trimsAnOverlongRawPayload() {
        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            longValue.append('x');
        }
        com.emotion.market.TencentClient.StockQuote quote = new com.emotion.market.TencentClient.StockQuote();
        quote.setCode("002909");
        quote.setLastPrice(new BigDecimal("5.54"));
        quote.setRawPayload(longValue.toString());

        StockAuction row = IntradayStore.toAuction(quote, DAY, CAPTURED);
        assertEquals(1000, row.getSnapshotRaw().length());
        assertTrue(row.getSnapshotRaw().endsWith("..."), "截断处要留标记，否则读的人会当成完整原文");
    }

    private static MinuteTick tick(int minute, String price, Long cumVolume, String cumAmount) {
        return new MinuteTick(minute, new BigDecimal(price), cumVolume,
                cumAmount == null ? null : new BigDecimal(cumAmount));
    }

    /** 一条完整盘口。字段比断言少时这里先红，好过线上少一列。 */
    private static final class TencentClientQuoteStub {
        com.emotion.market.TencentClient.StockQuote quote() {
            com.emotion.market.TencentClient.StockQuote q = new com.emotion.market.TencentClient.StockQuote();
            q.setSymbol("sz002909");
            q.setCode("002909");
            q.setName("集泰股份");
            q.setLastPrice(new BigDecimal("5.54"));
            q.setPrevClose(new BigDecimal("5.50"));
            q.setVolumeHand(1200L);
            q.setAmountWan(new BigDecimal("0.66"));
            q.setBid1Price(new BigDecimal("5.54"));
            q.setBid1Hand(800L);
            q.setAsk1Price(new BigDecimal("5.55"));
            q.setAsk1Hand(0L);
            q.setLimitUpPrice(new BigDecimal("6.05"));
            q.setRawPayload("1~集泰股份~002909~5.54");
            return q;
        }
    }
}
