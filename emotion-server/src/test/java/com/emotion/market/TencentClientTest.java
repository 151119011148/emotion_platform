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
            "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get",
            "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/minute/query", 12,
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

    // ---- 分钟序列 ----
    // fixture 是 2026-10-28 真实响应存档（sz002909，date=20261028，267 根，首根 0930、末根 1530）。

    /**
     * 累计口径原样交出，不在客户端差分。
     *
     * <p>午间休市会让同一分钟量连着出现两根（实测 1130 与 1300 的累计量都是 92502 手），
     * 差分在这两点会得到 0——那是对的；但如果在写入侧提前差分，读侧就再也还原不出「到这一分钟为止
     * 成交了多少」，而后者才是分时图下方那根量柱想要的东西。所以存累计，判据留给读侧。
     */
    @Test
    void parsesRealMinuteSeriesAndKeepsCumulativeColumns() {
        TencentClient.MinuteSeries series =
                client.parseMinuteSeries(Fixtures.text("minute_sz002909.json"), "sz002909");

        assertEquals(LocalDate.of(2026, 10, 28), series.getDate());
        assertEquals(267, series.getTicks().size());

        TencentClient.MinuteTick first = series.getTicks().get(0);
        assertEquals(Integer.valueOf(930), first.getMinute());
        assertEquals(0, new BigDecimal("5.54").compareTo(first.getPrice()));
        assertEquals(Long.valueOf(254L), first.getCumVolumeHand());
        assertEquals(0, new BigDecimal("140716.00").compareTo(first.getCumAmountYuan()));

        TencentClient.MinuteTick last = series.getTicks().get(series.getTicks().size() - 1);
        assertEquals(Integer.valueOf(1530), last.getMinute());
        assertEquals(Long.valueOf(151403L), last.getCumVolumeHand());
    }

    /** 一根都不能少：265→267 是收盘后又补了两根，序列长度只用来核对上游给了几天，不参与判据。 */
    @Test
    void minuteTicksAreMonotonicallyTimedInRawSourceOrder() {
        List<TencentClient.MinuteTick> ticks =
                client.parseMinuteSeries(Fixtures.text("minute_sz002909.json"), "sz002909").getTicks();

        int lunchBreak = 0;
        for (int i = 1; i < ticks.size(); i++) {
            assertTrue(ticks.get(i).getMinute() >= ticks.get(i - 1).getMinute(),
                    "第 " + i + " 根时间倒流：" + ticks.get(i - 1).getMinute() + " → " + ticks.get(i).getMinute());
            if (ticks.get(i).getCumVolumeHand().equals(ticks.get(i - 1).getCumVolumeHand())) {
                lunchBreak++;
            }
        }
        // 午休那一根不新增成交，靠这个确认累计列真读出来了、不是全 null
        assertTrue(lunchBreak > 0, "累计量一根都没重复，怀疑第 3、4 列没解出来");
    }

    /** 结构不对、代码不对、响应为空——一律给空序列，绝不让一只票把整趟循环带下水。 */
    @Test
    void malformedMinuteBodyYieldsEmptySeries() {
        assertTrue(client.parseMinuteSeries(null, "sz002909").isEmpty());
        assertTrue(client.parseMinuteSeries("  ", "sz002909").isEmpty());
        assertTrue(client.parseMinuteSeries("{ not json", "sz002909").isEmpty());
        assertTrue(client.parseMinuteSeries("{\"code\":0,\"data\":{}}", "sz002909").isEmpty());
        // data 下按代码取；换个代码去查同一份存档就是查不到，不能拿别人的序列冒充
        assertTrue(client.parseMinuteSeries(Fixtures.text("minute_sz002909.json"), "sh600000").isEmpty());
    }

    @Test
    void emptyTickListIsNotEmptySeriesGuard() {
        assertTrue(client.parseMinuteSeries(
                "{\"data\":{\"sz002909\":{\"data\":{\"date\":\"20261028\",\"data\":[]}}}}", "sz002909").isEmpty());
    }

    /**
     * 一根坏了只丢这一根。
     *
     * <p>补 0 是更糟的选择：一根假的 0 价在分时上画成断崖，会读成「这一分钟跳水」；
     * 缺一根只是这一分钟没采样。量/额两列缺失则留 null，由读侧自己决定怎么对待。
     */
    @Test
    void parsesAndDropsIndividualTicks() {
        LocalDate day = LocalDate.of(2026, 10, 28);

        TencentClient.MinuteTick tick = TencentClient.parseTick("0930 10.74 42064 45176736.00", day);
        assertEquals(Integer.valueOf(930), tick.getMinute());
        assertEquals(0, new BigDecimal("10.74").compareTo(tick.getPrice()));
        assertEquals(Long.valueOf(42064L), tick.getCumVolumeHand());
        assertEquals(0, new BigDecimal("45176736.00").compareTo(tick.getCumAmountYuan()));

        // 上游偶尔只给两列（刚开盘那根），量额留 null 而不是 0
        TencentClient.MinuteTick thin = TencentClient.parseTick("0930 10.74", day);
        assertEquals(0, new BigDecimal("10.74").compareTo(thin.getPrice()));
        assertNull(thin.getCumVolumeHand());
        assertNull(thin.getCumAmountYuan());

        assertNull(TencentClient.parseTick(null, day));
        assertNull(TencentClient.parseTick("", day));
        assertNull(TencentClient.parseTick("0930", day));          // 连价格都没有
        assertNull(TencentClient.parseTick("abcd 10.74", day));    // 时间不成形
        assertNull(TencentClient.parseTick("0930 x10.74", day));   // 价不是数，别当 0 存
    }

    /** 时间既给 "0930" 也可能给带秒的 "093000"，两种都要落到 930。 */
    @Test
    void normalizesMinuteWithOrWithoutSeconds() {
        assertEquals(Integer.valueOf(930), TencentClient.toMinute("0930"));
        assertEquals(Integer.valueOf(1530), TencentClient.toMinute("153000"));
        assertEquals(Integer.valueOf(930), TencentClient.toMinute(" 093000 "));
        // 带冒号的写法上游没给过，但同一套归一化顺手就吃下了——记下这个行为，别当成 bug
        assertEquals(Integer.valueOf(930), TencentClient.toMinute("09:30:00"));
        assertNull(TencentClient.toMinute("093"));
        assertNull(TencentClient.toMinute("abcd"));
        assertNull(TencentClient.toMinute(null));
    }

    /** date 解不出来就交 null，由调用方判「这不是今天的序列」；不能拿今天的日子去套它。 */
    @Test
    void readsSeriesDateOrGivesNull() {
        assertEquals(LocalDate.of(2026, 10, 28), TencentClient.toDecimalDate("20261028"));
        assertNull(TencentClient.toDecimalDate("2026102"));
        assertNull(TencentClient.toDecimalDate("abcdefgh"));
        assertNull(TencentClient.toDecimalDate(null));
    }

    // ---- 竞价快照要用的盘口字段位 ----

    /**
     * 浦发银行那行 88 段：现价 9.43 / 昨收 9.27 / 今开 9.27 / 成交量 757660 手 /
     * 买一 9.42×2773 手 / 卖一 9.43×8198 手 / 涨停 10.20（= 9.27×1.1 取整）。
     * 位子是按真实存档一个段一个段对出来的，改任何一位都要回到这段注释。
     */
    @Test
    void readsAuctionFieldsFromRealQuoteArchive() {
        TencentClient.StockQuote spdb = parseSample().get("sh600000");

        assertEquals("浦发银行", spdb.getName());
        assertEquals("600000", spdb.getCode());
        assertEquals(0, new BigDecimal("9.43").compareTo(spdb.getLastPrice()));
        assertEquals(0, new BigDecimal("9.27").compareTo(spdb.getPrevClose()));
        assertEquals(0, new BigDecimal("9.27").compareTo(spdb.getOpenPrice()));
        assertEquals(Long.valueOf(757660L), spdb.getVolumeHand());
        assertEquals(0, new BigDecimal("9.42").compareTo(spdb.getBid1Price()));
        assertEquals(Long.valueOf(2773L), spdb.getBid1Hand());
        assertEquals(0, new BigDecimal("9.43").compareTo(spdb.getAsk1Price()));
        assertEquals(Long.valueOf(8198L), spdb.getAsk1Hand());
        // 涨停价独立给出来，判「竞价即涨停」就不该在这里再乘一次系数（板块幅度 10%/20%/30% 不一）
        assertEquals(0, new BigDecimal("10.20").compareTo(spdb.getLimitUpPrice()));
        assertEquals("20260904161446", spdb.getQuoteTime());
        assertTrue(spdb.getRawPayload().contains("浦发银行"));
    }

    /**
     * 跌停封死的集泰股份：买一是 0.00×0（没人挂买），卖一还挂着 1849 手，现价正好等于跌停价。
     * 这一行存在的意义是「字段位不是我想的那样」的反例——0 在这里是真值，不是缺数。
     */
    @Test
    void keepsZeroBidAsARealValueNotAnAbsence() {
        TencentClient.StockQuote jitai = parseSample().get("sz002909");

        assertEquals(0, new BigDecimal("7.21").compareTo(jitai.getLastPrice()));
        assertEquals(0, new BigDecimal("0.00").compareTo(jitai.getBid1Price()));
        assertEquals(Long.valueOf(0L), jitai.getBid1Hand());
        assertEquals(0, new BigDecimal("7.21").compareTo(jitai.getAsk1Price()));
        assertEquals(Long.valueOf(1849L), jitai.getAsk1Hand());
        assertEquals(0, new BigDecimal("8.81").compareTo(jitai.getLimitUpPrice()));
    }

    /** 指数行第 48 段是 -1 这种哨兵值，五档也全 0：读到什么存什么，不在这里替它编一个。 */
    @Test
    void indexRowsParseWithoutThrowingEvenThoughTheirBookIsSentinels() {
        TencentClient.StockQuote sh = parseSample().get("sh000001");

        assertEquals(0, new BigDecimal("3930.12").compareTo(sh.getLastPrice()));
        assertEquals(0, new BigDecimal("-1").compareTo(sh.getLimitUpPrice()));
        assertEquals(Long.valueOf(537286161L), sh.getVolumeHand());
    }

    /**
     * 只到第 38 段的行：老字段照收，读不出数的新位一律 null，第 47 段的涨停价根本不存在也是 null。
     *
     * <p>这条守的是「长度」而不是「内容」。溢价那批调用方在 38 段之后就停了，而竞价要的位子排在
     * 更后面（涨停价在第 47 段）；上游哪天给一行短的，越界不能把整批样本一起带走。
     */
    @Test
    void shortLineKeepsTheOldFieldsAndNullsTheNewOnes() {
        Map<String, TencentClient.StockQuote> quotes = new HashMap<>();
        String[] seg = new String[38];
        Arrays.fill(seg, "x");        // 每段都在，但都不是数
        seg[1] = "测试股";
        seg[2] = "000900";
        seg[32] = "1.01";
        client.parseInto(quotes, "v_sz000900=\"" + String.join("~", seg) + "\"\n");

        assertEquals(1, quotes.size());
        TencentClient.StockQuote shortLine = quotes.get("sz000900");
        assertEquals("000900", shortLine.getCode());
        assertEquals(0, new BigDecimal("1.01").compareTo(shortLine.getChangePct()));
        assertNull(shortLine.getLastPrice());
        assertNull(shortLine.getBid1Price());
        assertNull(shortLine.getAsk1Hand());
        assertNull(shortLine.getVolumeHand());
        assertNull(shortLine.getLimitUpPrice());
    }

    /** 48 段之后的位子真给到了，就一段不少地读出来（这一段是 09:26 快照的全部依据）。 */
    @Test
    void readsTheHighSegmentsWhenTheLineIsLongEnough() {
        Map<String, TencentClient.StockQuote> quotes = new HashMap<>();
        String[] seg = new String[88];
        Arrays.fill(seg, "x");
        seg[1] = "测试股";
        seg[2] = "000999";
        seg[3] = "10.00";
        seg[4] = "9.90";
        seg[5] = "9.95";
        seg[6] = "12345";
        seg[9] = "9.99";
        seg[10] = "100";
        seg[19] = "10.01";
        seg[20] = "50";
        seg[30] = "20261028092600";
        seg[32] = "1.01";
        seg[37] = "113502";
        seg[47] = "11.00";
        client.parseInto(quotes, "v_sz000999=\"" + String.join("~", seg) + "\"\n");

        TencentClient.StockQuote q = quotes.get("sz000999");
        assertEquals("测试股", q.getName());
        assertEquals("000999", q.getCode());
        assertEquals(0, new BigDecimal("10.00").compareTo(q.getLastPrice()));
        assertEquals(0, new BigDecimal("9.95").compareTo(q.getOpenPrice()));
        assertEquals(Long.valueOf(12345L), q.getVolumeHand());
        assertEquals(0, new BigDecimal("9.99").compareTo(q.getBid1Price()));
        assertEquals(Long.valueOf(100L), q.getBid1Hand());
        assertEquals(0, new BigDecimal("10.01").compareTo(q.getAsk1Price()));
        assertEquals(Long.valueOf(50L), q.getAsk1Hand());
        assertEquals(0, new BigDecimal("11.00").compareTo(q.getLimitUpPrice()));
        assertEquals(0, new BigDecimal("9.90").compareTo(q.getPrevClose()));
        assertEquals("20261028092600", q.getQuoteTime());
        // 原文整行留着：09:26 那刻的字段语义是我按收盘存档推断的，判读要改时得能重算而不是重抓
        assertEquals("测试股", q.getRawPayload().split("~")[1]);
    }
}
