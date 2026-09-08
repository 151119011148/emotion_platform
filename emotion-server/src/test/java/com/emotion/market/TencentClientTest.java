package com.emotion.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 腾讯行情的 GBK 切分与两市成交额口径，喂的是真实响应字节存档。 */
class TencentClientTest {

    private final TencentClient client = new TencentClient(null, "http://qt.gtimg.cn", 50,
            "sh000001", "sz399001",
            "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get", 12,
            TencentClient.DEFAULT_INDEX_CLOSE_CODES);

    private Map<String, TencentClient.StockQuote> parseSample() {
        Map<String, TencentClient.StockQuote> quotes = new HashMap<>();
        client.parseInto(quotes, Fixtures.gbkText("quotes_gbk_sample.raw"));
        return quotes;
    }

    /** key 必须是带前缀的代码：按载荷里的裸代码建索引，指数那两条就永远查不到。 */
    @Test
    void keysQuotesByPrefixedSymbolAndReadsChineseNames() {
        Map<String, TencentClient.StockQuote> quotes = parseSample();

        assertEquals(4, quotes.size());
        assertTrue(quotes.containsKey("sh000001"));
        assertTrue(quotes.containsKey("sz399001"));
        assertEquals("000001", quotes.get("sh000001").getCode());
        assertEquals(0, new BigDecimal("-0.30").compareTo(quotes.get("sh000001").getChangePct()));
    }

    /** 快照日读上游给的时间戳：今天 09-05 是周六，上游给的是 09-04。 */
    @Test
    void readsSnapshotDateFromUpstreamTimestamp() {
        assertEquals(LocalDate.of(2026, 9, 4),
                TencentClient.snapshotDate(parseSample(), "sh000001"));
    }

    /** 深证成指给的是全深市口径（与深证综指实测完全相同），所以沪+深可以直接相加。 */
    @Test
    void sumsTwoMarketAmountInBillion() {
        BigDecimal amount = TencentClient.twoMarketAmountBillion(parseSample(), "sh000001", "sz399001");

        // 93825519 万 + 109241265 万 = 20306.68 亿
        assertEquals(0, new BigDecimal("20306.68").compareTo(amount));
    }

    @Test
    void refusesToReportAPartialAmount() {
        Map<String, TencentClient.StockQuote> onlySh = new HashMap<>();
        onlySh.put("sh000001", parseSample().get("sh000001"));

        assertNull(TencentClient.twoMarketAmountBillion(onlySh, "sh000001", "sz399001"));
    }

    @Test
    void ignoresMalformedLines() {
        Map<String, TencentClient.StockQuote> quotes = new HashMap<>();
        client.parseInto(quotes, "not a quote line\nv_sh000001=\"1~上证指数~000001~1\"\n");

        assertTrue(quotes.isEmpty());
    }

    /** 北交所新段 920xxx 在东财的 market 字段里仍是 0，只能靠代码前缀认出来——否则它会被送去 sz，永远取不到报价。 */
    @Test
    void mapsPoolRowsToGtimgSymbols() {
        List<PoolRow> rows = Arrays.asList(
                row("600000", 1), row("002909", 0), row("301199", 0), row("430047", 0),
                row("830799", 0), row("920895", 0));

        assertEquals(Arrays.asList("sh600000", "sz002909", "sz301199", "bj430047", "bj830799", "bj920895"),
                TencentClient.toGtimgCodes(rows));
    }

    /** 日 K 回溯出的 09-04 必须与实时快照同一口径：9382.55 + 10924.13 = 20306.68 亿。 */
    @Test
    void readsHistoricalTwoMarketAmountFromDailyKline() {
        String body = Fixtures.text("kline_day_amount.json");
        LocalDate day = LocalDate.parse(Fixtures.TRADE_DATE);

        assertEquals(0, new BigDecimal("9382.55")
                .compareTo(client.parseDayAmount(body, "sh000001", day)));
        assertEquals(0, new BigDecimal("10924.13")
                .compareTo(client.parseDayAmount(body, "sz399001", day)));
    }

    /** 请求日不在这根柱子上就是没有，不能拿相邻交易日顶替。 */
    @Test
    void returnsNullWhenRequestedDayIsAbsentFromKline() {
        String body = Fixtures.text("kline_day_amount.json");

        assertNull(client.parseDayAmount(body, "sh000001", LocalDate.parse("2026-08-29")));
        assertNull(client.parseDayAmount(body, "sh999999", LocalDate.parse(Fixtures.TRADE_DATE)));
        assertNull(client.parseDayAmount(null, "sh000001", LocalDate.parse(Fixtures.TRADE_DATE)));
        assertNull(client.parseDayAmount("{ not json", "sh000001", LocalDate.parse(Fixtures.TRADE_DATE)));
    }

    private static final String BARS = "kline_daily_600664.json";
    private static final String BAR_SYMBOL = "sh600664";

    /** 上游忽略 start、只给"截至 end 的最近 N 根"，所以截窗在客户端做，且窗口第一天照样要有涨幅。 */
    @Test
    void cutsWindowButStillPricesItsFirstDay() {
        List<TencentClient.DayBar> bars = client.parseDailyBars(Fixtures.text(BARS), BAR_SYMBOL,
                LocalDate.parse("2026-08-17"), LocalDate.parse(Fixtures.TRADE_DATE));

        assertEquals(15, bars.size());
        assertEquals(LocalDate.parse("2026-08-17"), bars.get(0).getDate());
        assertEquals(0, new BigDecimal("1.12").compareTo(bars.get(0).getPct()));
        // 窗口外那根（08-14）只是拿来算涨幅，不能混进结果里
        assertEquals(LocalDate.parse(Fixtures.TRADE_DATE), bars.get(bars.size() - 1).getDate());
    }

    /**
     * 哈药 09-03：收盘 -7.47% 看着只是大跌，最低价却到了 -9.96% —— 盘中触板。
     * 阵眼那一维给 0 分还是 1 分就卡在这条线上，所以盘中最低必须单独交出去。
     */
    @Test
    void readsTouchOfLimitFromTheIntradayLowNotTheClose() {
        List<TencentClient.DayBar> bars = client.parseDailyBars(Fixtures.text(BARS), BAR_SYMBOL,
                LocalDate.parse(Fixtures.PREV_TRADE_DATE), LocalDate.parse(Fixtures.TRADE_DATE));

        assertEquals(0, new BigDecimal("-7.47").compareTo(bars.get(0).getPct()));
        assertEquals(0, new BigDecimal("-9.96").compareTo(bars.get(0).getLowPct()));
        // 08-21 是收在板上的跌停：收盘涨幅与最低涨幅同为 -10.02%
        List<TencentClient.DayBar> crash = client.parseDailyBars(Fixtures.text(BARS), BAR_SYMBOL,
                LocalDate.parse("2026-08-21"), LocalDate.parse("2026-08-21"));
        assertEquals(0, new BigDecimal("-10.02").compareTo(crash.get(0).getPct()));
        assertEquals(0, new BigDecimal("-10.02").compareTo(crash.get(0).getLowPct()));
    }

    /** 序列里第一根没有前收可比，就只能没有涨幅——不能拿开盘价凑一个出来。 */
    @Test
    void firstBarOfTheSeriesHasNoPercent() {
        List<TencentClient.DayBar> bars = client.parseDailyBars(Fixtures.text(BARS), BAR_SYMBOL,
                LocalDate.parse("2026-08-12"), LocalDate.parse("2026-08-13"));

        assertNull(bars.get(0).getPct());
        assertNull(bars.get(0).getLowPct());
        assertEquals(0, new BigDecimal("0.57").compareTo(bars.get(1).getPct()));
        assertEquals(0, new BigDecimal("8.81").compareTo(bars.get(0).getClose()));
    }

    @Test
    void brokenDailyBodyYieldsNoBarsRatherThanHalfAWindow() {
        LocalDate start = LocalDate.parse("2026-08-17");
        LocalDate end = LocalDate.parse(Fixtures.TRADE_DATE);

        assertTrue(client.parseDailyBars(null, BAR_SYMBOL, start, end).isEmpty());
        assertTrue(client.parseDailyBars("{ not json", BAR_SYMBOL, start, end).isEmpty());
        assertTrue(client.parseDailyBars(Fixtures.text(BARS), "sz000001", start, end).isEmpty());
    }

    private static PoolRow row(String code, int market) {
        PoolRow row = new PoolRow();
        row.setCode(code);
        row.setMarket(market);
        return row;
    }
}
