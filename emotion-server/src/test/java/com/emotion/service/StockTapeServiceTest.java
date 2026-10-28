package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emotion.entity.DailyBar;
import com.emotion.entity.MarketStock;
import com.emotion.entity.StockAuction;
import com.emotion.entity.StockMinute;
import com.emotion.exception.BizException;
import com.emotion.mapper.DailyBarMapper;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.StockAuctionMapper;
import com.emotion.mapper.StockMinuteMapper;
import com.emotion.vo.StockTapeVO;

/**
 * 分时读接口（{@link StockTapeService}）：断言集中在<strong>读时算出来的那三样</strong>
 * 与<strong>「为什么没有」到底说没说中</strong>上。
 *
 * <p>这个类一行上游都不碰——分钟序列的源只给当天，缺了就是永久缺，所以读侧唯一能做对的事
 * 是把缺数分型讲清楚。测试因此盯两条：① 累计列差分／均价／涨幅的算式；
 * ② 四种缺数各有各的一句话，且不许任何一句把「这天抓了但没这只」说成「这天没抓」。
 */
class StockTapeServiceTest {

    private static final String CODE = "002909";
    private static final String SYMBOL = "sz002909";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final LocalDate PREV_TRADING_DAY = LocalDate.of(2026, 10, 27);

    private StockMinuteMapper minuteMapper;
    private StockAuctionMapper auctionMapper;
    private MarketStockMapper marketStockMapper;
    private DailyBarMapper dailyBarMapper;
    private StockTapeService service;

    @BeforeEach
    void setUp() {
        minuteMapper = mock(StockMinuteMapper.class);
        auctionMapper = mock(StockAuctionMapper.class);
        marketStockMapper = mock(MarketStockMapper.class);
        dailyBarMapper = mock(DailyBarMapper.class);
        service = new StockTapeService(minuteMapper, auctionMapper, marketStockMapper, dailyBarMapper);
    }

    /** 库里只有累计列，逐分钟量与均价都是读时减出来的。 */
    @Test
    void diffsCumulativeColumnsIntoPerMinuteVolumeAndVwap() {
        stubBars(bar(930, "10.00", 1000L, "1005000"),
                bar(931, "10.10", 1600L, "1616000"),
                bar(932, "10.05", 1600L, "1616000"));

        StockTapeVO vo = service.tape(CODE, DAY);

        assertTrue(vo.isAvailable());
        assertEquals(3, vo.getPoints().size());
        // 首根的差值就是它自己的累计值——那里面含竞价撮合的量，本来就该在
        assertEquals(Long.valueOf(1000L), vo.getPoints().get(0).getVolumeHand());
        assertEquals(new BigDecimal("100.50"), vo.getPoints().get(0).getAmountWan());
        assertEquals(Long.valueOf(600L), vo.getPoints().get(1).getVolumeHand());
        assertEquals(new BigDecimal("61.10"), vo.getPoints().get(1).getAmountWan());
        // 均价 = 累计额 ÷ (累计手 × 100)：1616000 ÷ 160000 = 10.100
        assertEquals(new BigDecimal("10.100"), vo.getPoints().get(1).getVwap());
        // 这一分钟没有成交：量为 0，价格与均价照给——不是把整根丢掉
        assertEquals(Long.valueOf(0L), vo.getPoints().get(2).getVolumeHand());
        assertEquals(new BigDecimal("0.00"), vo.getPoints().get(2).getAmountWan());
        assertEquals("09:30", vo.getPoints().get(0).getTime());
        assertEquals("09:32", vo.getPoints().get(2).getTime());
    }

    /** 涨幅分母首选 09:26 快照里的 prev_close：源给的不复权原值。 */
    @Test
    void prefersTheAuctionSnapshotPrevClose() {
        stubBars(bar(930, "11.00", 100L, "110000"));
        when(auctionMapper.selectForDate(CODE, DAY))
                .thenReturn(auction("10.00", "11.00", "建艺集团"));

        StockTapeVO vo = service.tape(CODE, DAY);

        assertEquals(new BigDecimal("10.00"), vo.getPrevClose());
        assertEquals(StockTapeVO.PREV_AUCTION, vo.getPrevCloseSource());
        assertEquals(new BigDecimal("10.00"), vo.getPoints().get(0).getPct());
        assertEquals(new BigDecimal("10.00"), vo.getAuction().getPct());
        assertEquals("建艺集团", vo.getName());
        assertTrue(vo.getAuction().isAvailable());
    }

    /**
     * 首日没有竞价快照（09:26 那趟从第二天才开始排），当日明细的收盘与涨幅还能反推出昨收。
     * 10-28 晚上第一次点进分时，靠的就是这一条。
     */
    @Test
    void derivesPrevCloseFromTheSameDayDetailRow() {
        stubBars(bar(930, "11.00", 100L, "110000"));
        MarketStock row = new MarketStock();
        row.setName("建艺集团");
        row.setClosePrice(new BigDecimal("12.10"));
        row.setChangePct(new BigDecimal("10.00"));
        when(marketStockMapper.rowOfCodeOn(CODE, DAY)).thenReturn(row);

        StockTapeVO vo = service.tape(CODE, DAY);

        assertEquals(0, new BigDecimal("11.000").compareTo(vo.getPrevClose()));
        assertEquals(StockTapeVO.PREV_MARKET_STOCK, vo.getPrevCloseSource());
        assertEquals(new BigDecimal("0.00"), vo.getPoints().get(0).getPct());
        assertEquals("建艺集团", vo.getName());
    }

    /**
     * 日 K 那条来路只在它<strong>真的就是前一交易日</strong>时才认。
     *
     * <p>{@code t_daily_bar} 按窗口懒填充，取到一根一个月前的柱子当昨收，画出来的就不是分时涨幅
     * 而是月涨幅；这张表还是 qfq 前复权，中间除过权会整体偏低。宁可没有分母。
     */
    @Test
    void acceptsADailyBarOnlyWhenItIsThePreviousTradingDay() {
        stubBars(bar(930, "11.00", 100L, "110000"));
        when(marketStockMapper.prevDetailDate(DAY)).thenReturn(PREV_TRADING_DAY);
        when(dailyBarMapper.lastBefore(SYMBOL, DAY)).thenReturn(barOf(PREV_TRADING_DAY, "10.00"));

        assertEquals(StockTapeVO.PREV_DAILY_BAR, service.tape(CODE, DAY).getPrevCloseSource());

        when(dailyBarMapper.lastBefore(SYMBOL, DAY)).thenReturn(barOf(DAY.minusDays(30), "10.00"));
        StockTapeVO vo = service.tape(CODE, DAY);

        assertEquals(StockTapeVO.PREV_NONE, vo.getPrevCloseSource());
        assertNull(vo.getPrevClose());
        assertNull(vo.getPoints().get(0).getPct());
        // 缺分母只关掉「涨幅」这一维，价格线照画
        assertTrue(vo.isAvailable());
        assertEquals(new BigDecimal("11.00"), vo.getPoints().get(0).getPrice());
    }

    /** 早于库里第一天：源只给当天，这一天永久没有，话要说死。 */
    @Test
    void explainsADayStoredNothingBecauseItPredatesTheFeature() {
        LocalDate first = DAY;
        when(minuteMapper.selectDay(CODE, first.minusDays(1))).thenReturn(Collections.emptyList());
        when(minuteMapper.firstStoredDate()).thenReturn(first);

        StockTapeVO vo = service.tape(CODE, first.minusDays(1));

        assertFalse(vo.isAvailable());
        assertTrue(vo.getEmptyReason().contains(String.valueOf(first)), vo.getEmptyReason());
        assertTrue(vo.getEmptyReason().contains("补不回来"), vo.getEmptyReason());
    }

    /** 那天抓了 45 只但没有这一只——和「那天一只都没抓」是两句话。 */
    @Test
    void distinguishesAnUncapturedDayFromACodeThatWasNotSwept() {
        when(minuteMapper.selectDay(CODE, DAY)).thenReturn(Collections.emptyList());
        when(minuteMapper.countCodesOn(DAY)).thenReturn(45);

        String reason = service.tape(CODE, DAY).getEmptyReason();

        assertTrue(reason.contains("45"), reason);
        assertTrue(reason.contains("涨停池"), reason);
    }

    @Test
    void weekendGetsItsOwnReason() {
        LocalDate saturday = LocalDate.of(2026, 10, 24);
        when(minuteMapper.selectDay(CODE, saturday)).thenReturn(Collections.emptyList());

        assertTrue(service.tape(CODE, saturday).getEmptyReason().contains("周末"));
    }

    /** 竞价那一格自己也要分型：09:25–09:30 只有五分钟窗口，错过就是永久错过。 */
    @Test
    void auctionAbsenceExplainsWhetherTheWholeDayOrJustThisStock() {
        stubBars(bar(930, "11.00", 100L, "110000"));
        when(auctionMapper.countCodesOn(DAY)).thenReturn(0);

        StockTapeVO.Auction none = service.tape(CODE, DAY).getAuction();

        assertFalse(none.isAvailable());
        assertTrue(none.getEmptyReason().contains("永久"), none.getEmptyReason());

        when(auctionMapper.countCodesOn(DAY)).thenReturn(38);
        assertTrue(service.tape(CODE, DAY).getAuction().getEmptyReason().contains("38"));
    }

    /** 竞价一字封死：成交价等于当日涨停价。缺任一个值是 null（不知道），不是 false（没封死）。 */
    @Test
    void sealedAtOpenAnswersOnlyWhenBothValuesAreThere() {
        stubBars(bar(930, "11.00", 100L, "110000"));
        StockAuction a = auction(null, "12.10", null);
        a.setLimitUpPrice(new BigDecimal("12.10"));
        when(auctionMapper.selectForDate(CODE, DAY)).thenReturn(a);

        assertEquals(Boolean.TRUE, service.tape(CODE, DAY).getAuction().getSealedAtOpen());

        a.setLimitUpPrice(new BigDecimal("12.11"));
        assertEquals(Boolean.FALSE, service.tape(CODE, DAY).getAuction().getSealedAtOpen());

        a.setLimitUpPrice(null);
        assertNull(service.tape(CODE, DAY).getAuction().getSealedAtOpen());
    }

    /** 代码不合法必须是中文原因：{@code GlobalExceptionHandler} 把 message 原样弹到界面上。 */
    @Test
    void rejectsCodeThatIsNotSixDigits() {
        BizException e = assertThrows(BizException.class, () -> service.tape("sh002909", DAY));
        assertTrue(e.getMessage().contains("6 位数字"), e.getMessage());
        assertThrows(BizException.class, () -> service.tape(null, DAY));
    }

    @Test
    void formatsHourAndMinuteIntoClockText() {
        assertEquals("09:30", StockTapeService.hhmm(930));
        assertEquals("11:30", StockTapeService.hhmm(1130));
        assertEquals("15:00", StockTapeService.hhmm(1500));
        assertNull(StockTapeService.hhmm(null));
        assertNull(StockTapeService.hhmm(9999));
    }

    private void stubBars(StockMinute... bars) {
        when(minuteMapper.selectDay(CODE, DAY)).thenReturn(Arrays.asList(bars));
    }

    private static StockAuction auction(String prevClose, String price, String name) {
        StockAuction a = new StockAuction();
        a.setCode(CODE);
        a.setTradeDate(DAY);
        a.setName(name);
        a.setPrevClose(prevClose == null ? null : new BigDecimal(prevClose));
        a.setAuctionPrice(new BigDecimal(price));
        return a;
    }

    private static StockMinute bar(int minute, String price, Long cumHand, String cumAmountYuan) {
        StockMinute b = new StockMinute();
        b.setCode(CODE);
        b.setTradeDate(DAY);
        b.setMinute(minute);
        b.setPrice(new BigDecimal(price));
        b.setCumVolumeHand(cumHand);
        b.setCumAmountYuan(new BigDecimal(cumAmountYuan));
        return b;
    }

    private static DailyBar barOf(LocalDate date, String close) {
        DailyBar bar = new DailyBar();
        bar.setSymbol(SYMBOL);
        bar.setTradeDate(date);
        bar.setClosePrice(new BigDecimal(close));
        return bar;
    }
}
