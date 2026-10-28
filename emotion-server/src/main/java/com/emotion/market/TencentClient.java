package com.emotion.market;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 腾讯行情。实测它是这批免费源里唯一稳定可用的，且一次请求能带上百只代码——
 * "昨日涨停溢价"因此只需要一次网络往返，这对规避上游限流至关重要。
 *
 * 快照接口只给最新一天，回溯不了历史；所以历史交易日的成交额另走日 K 接口。
 */
@Component
public class TencentClient {

    private static final Logger log = LoggerFactory.getLogger(TencentClient.class);

    /** 腾讯返回 GBK；JDK 里 GBK/GB18030 一定存在，兜底只是防御性写法。 */
    private static final Charset GBK = resolveGbk();
    private static final DateTimeFormatter QUOTE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final int IDX_CODE = 2;
    private static final int IDX_NAME = 1;
    private static final int IDX_PREV_CLOSE = 4;
    private static final int IDX_QUOTE_TIME = 30;
    private static final int IDX_CHANGE_PCT = 32;
    private static final int IDX_AMOUNT_WAN = 37;
    private static final int MIN_FIELD_COUNT = 38;

    /**
     * 竞价快照要用的字段位。2026-10-28 拿百洋股份(002696)那行 88 段逐个对过：
     * 现价 11.77 / 昨收 10.70 / 今开 10.74 / 成交量 1124824 手 / 买一 11.77×3911 手 /
     * 卖一 11.77×0 手 / 成交额 120210 万 / 涨停 11.77 对 10.70×1.1 ✓ / 跌停 9.63 对 10.70×0.9 ✓。
     *
     * <p>第 47 段已经在 {@link #MIN_FIELD_COUNT} 之外，而每一行是否真有 48 段以上取决于上游当天
     * 给的是什么品种，所以读这些位子必须按行长度兜底——读越界只会拿到 null，
     * 不能让整次快照（包括已经拿到的溢价样本）跟着一起崩。
     */
    private static final int IDX_LAST_PRICE = 3;
    private static final int IDX_OPEN_PRICE = 5;
    private static final int IDX_VOLUME_HAND = 6;
    private static final int IDX_BID1_PRICE = 9;
    private static final int IDX_BID1_HAND = 10;
    private static final int IDX_ASK1_PRICE = 19;
    private static final int IDX_ASK1_HAND = 20;
    private static final int IDX_LIMIT_UP = 47;

    /** 日 K 每行 [日期,开,收,高,低,量,{},换手,成交额(万),..]。 */
    private static final int IDX_KLINE_CLOSE = 2;
    private static final int IDX_KLINE_LOW = 4;
    /** 两市成交额取的就是这一列。 */
    private static final int IDX_KLINE_AMOUNT = 8;
    private static final BigDecimal WAN = BigDecimal.valueOf(10_000L);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);

    /** 默认映射。生产配置和单测都引用它，免得两处各写一份、各自漂。 */
    static final String DEFAULT_INDEX_CLOSE_CODES =
            "sh000001:000001,sz399001:399001,sz399006:399006,sh000688:000688,bj899050:899050";

    private final HttpFetcher fetcher;
    private final String baseUrl;
    private final int chunkSize;
    private final String shIndex;
    private final String szIndex;
    private final String klineUrl;
    /** 分钟序列接口。与日 K 同一个主机（proxy.finance.qq.com），那个域在本机实测可达。 */
    private final String minuteUrl;
    private final int dayAmountBars;
    /** gtimg 码 → t_index_close.index_code。顺序就是导出文档里那五行的顺序。 */
    private final Map<String, String> indexCloseCodes;
    private final ObjectMapper mapper = new ObjectMapper();

    public TencentClient(HttpFetcher fetcher,
                         @Value("${market.tencent.base:http://qt.gtimg.cn}") String baseUrl,
                         @Value("${market.tencent.chunk-size:50}") int chunkSize,
                         @Value("${market.tencent.sh-index:sh000001}") String shIndex,
                         @Value("${market.tencent.sz-index:sz399001}") String szIndex,
                         @Value("${market.tencent.kline-url:https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get}") String klineUrl,
                         @Value("${market.tencent.minute-url:https://proxy.finance.qq.com/ifzqgtimg/appstock/app/minute/query}") String minuteUrl,
                         @Value("${market.tencent.day-amount-bars:12}") int dayAmountBars,
                         @Value("${market.tencent.index-close-codes:"
                                 + DEFAULT_INDEX_CLOSE_CODES + "}") String indexCloseCodes) {
        this.fetcher = fetcher;
        this.baseUrl = baseUrl;
        this.chunkSize = Math.max(1, chunkSize);
        this.shIndex = shIndex;
        this.szIndex = szIndex;
        this.klineUrl = klineUrl;
        this.minuteUrl = minuteUrl;
        this.dayAmountBars = Math.max(2, dayAmountBars);
        this.indexCloseCodes = parseIndexCloseCodes(indexCloseCodes);
    }

    /**
     * "sh000001:000001,..." → 有序映射。坏段跳过而不是抛异常：这是配置，
     * 一个字符打错不该让整机起不来，缺的那只指数会在取数结果里少一行、看得见。
     */
    static Map<String, String> parseIndexCloseCodes(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null) {
            return map;
        }
        for (String part : raw.split(",")) {
            String seg = part.trim();
            int colon = seg.indexOf(':');
            if (colon <= 0 || colon == seg.length() - 1) {
                if (!seg.isEmpty()) {
                    log.warn("指数代码映射这一段不成对，已跳过: {}", seg);
                }
                continue;
            }
            map.put(seg.substring(0, colon).trim(), seg.substring(colon + 1).trim());
        }
        return map;
    }

    /** 配置里期望有几只指数。用于判断"这天取全了没有"。 */
    public int expectedIndexCount() {
        return indexCloseCodes.size();
    }

    /**
     * 五大指数的当日收盘与涨幅，key 是 {@code t_index_close.index_code}（不带市场前缀）。
     *
     * <p>走日 K 而不是实时快照：快照只有最新一天，而复盘要能回溯历史日子。指数序列没有复权问题，
     * 所以 {@code DayBar.pct}（前一根收盘推出来的）与实时 payload 的涨跌幅字段实测一字不差。
     * 一次一只：某只取不到只丢那一只，不该把其余四只一起放弃。
     */
    public Map<String, DayBar> indexDayBars(LocalDate date) {
        Map<String, DayBar> result = new LinkedHashMap<>();
        if (date == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : indexCloseCodes.entrySet()) {
            List<DayBar> bars = dailyBars(entry.getKey(), date, date);
            for (DayBar bar : bars) {
                if (date.equals(bar.getDate()) && bar.getClose() != null) {
                    result.put(entry.getValue(), bar);
                    break;
                }
            }
        }
        if (result.size() < indexCloseCodes.size()) {
            log.info("{} 指数收盘只取到 {}/{}", date, result.size(), indexCloseCodes.size());
        }
        return result;
    }

    private static Charset resolveGbk() {
        if (Charset.isSupported("GBK")) {
            return Charset.forName("GBK");
        }
        return Charset.isSupported("GB18030") ? Charset.forName("GB18030") : Charset.forName("ISO-8859-1");
    }

    /** 一次请求取一批代码；任何一片失败只丢那一片，不影响其余。 */
    public Map<String, StockQuote> quotes(List<String> gtimgCodes) {
        Map<String, StockQuote> result = new HashMap<>();
        if (gtimgCodes == null || gtimgCodes.isEmpty()) {
            return result;
        }
        for (int i = 0; i < gtimgCodes.size(); i += chunkSize) {
            List<String> chunk = gtimgCodes.subList(i, Math.min(i + chunkSize, gtimgCodes.size()));
            parseInto(result, request(chunk));
        }
        return result;
    }

    private String request(List<String> codes) {
        byte[] bytes = fetcher.getBytes(baseUrl + "/q=" + String.join(",", codes));
        return bytes == null ? null : new String(bytes, GBK);
    }

    /** 包级可见：单测直接喂存下来的 GBK 响应，不联网。 */
    void parseInto(Map<String, StockQuote> sink, String body) {
        if (body == null) {
            return;
        }
        for (String line : body.split("\n")) {
            // 变量名带市场前缀（v_sh000001=...），而载荷第 3 段只有裸代码 000001，
            // 两者必须区分开，否则按 sh000001 查指数永远查不到
            String symbol = extractSymbol(line);
            int open = line.indexOf('"');
            int close = line.lastIndexOf('"');
            if (symbol == null || open < 0 || close <= open) {
                continue;
            }
            String[] f = line.substring(open + 1, close).split("~", -1);
            if (f.length < MIN_FIELD_COUNT) {
                continue;
            }
            StockQuote quote = new StockQuote();
            quote.setSymbol(symbol);
            quote.setCode(f[IDX_CODE]);
            quote.setName(f[IDX_NAME]);
            quote.setChangePct(toDecimal(f[IDX_CHANGE_PCT]));
            quote.setPrevClose(toDecimal(f[IDX_PREV_CLOSE]));
            quote.setAmountWan(toDecimal(f[IDX_AMOUNT_WAN]));
            quote.setQuoteDate(toDate(f[IDX_QUOTE_TIME]));
            // 竞价快照要用的几段。逐段按长度取：老响应短于 48 段时拿到 null，
            // 而不是让整批快照（包括已经拿到的溢价样本）跟着越界异常一起没。
            quote.setLastPrice(toDecimal(at(f, IDX_LAST_PRICE)));
            quote.setOpenPrice(toDecimal(at(f, IDX_OPEN_PRICE)));
            quote.setVolumeHand(toLong(at(f, IDX_VOLUME_HAND)));
            quote.setBid1Price(toDecimal(at(f, IDX_BID1_PRICE)));
            quote.setBid1Hand(toLong(at(f, IDX_BID1_HAND)));
            quote.setAsk1Price(toDecimal(at(f, IDX_ASK1_PRICE)));
            quote.setAsk1Hand(toLong(at(f, IDX_ASK1_HAND)));
            quote.setLimitUpPrice(toDecimal(at(f, IDX_LIMIT_UP)));
            quote.setQuoteTime(at(f, IDX_QUOTE_TIME));
            quote.setRawPayload(line.substring(open + 1, close));
            sink.put(symbol, quote);
        }
    }

    /** 越界与空串都给 null：盘口字段位在不同品种上长度不一，缺就是缺。 */
    private static String at(String[] fields, int index) {
        return fields == null || index < 0 || index >= fields.length ? null : fields[index];
    }

    private static String extractSymbol(String line) {
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String name = line.substring(0, eq).trim();
        if (name.startsWith("v_")) {
            name = name.substring(2);
        }
        return name.isEmpty() ? null : name;
    }

    /** 指数快照，一次请求。调用方拿它同时算快照日和两市成交额，别调两次。 */
    public Map<String, StockQuote> indexQuotes() {
        Map<String, StockQuote> result = new HashMap<>();
        parseInto(result, request(Arrays.asList(shIndex, szIndex)));
        if (result.isEmpty()) {
            log.warn("腾讯指数行情未取到数据");
        }
        return result;
    }

    public String shIndexCode() {
        return shIndex;
    }

    public String szIndexCode() {
        return szIndex;
    }

    /** 快照日：直接读上游给的时间戳，而不是我们自己去猜"今天是不是交易日"。 */
    public static LocalDate snapshotDate(Map<String, StockQuote> indexes, String shIndex) {
        StockQuote sh = indexes.get(shIndex);
        return sh == null ? null : sh.getQuoteDate();
    }

    /** 两市成交额（亿元）：沪市指数 + 深市指数，实测深证成指与深证综指给的是同一个全市场口径。 */
    public static BigDecimal twoMarketAmountBillion(Map<String, StockQuote> indexes, String shIndex, String szIndex) {
        BigDecimal total = BigDecimal.ZERO;
        int found = 0;
        for (String code : new String[]{shIndex, szIndex}) {
            StockQuote quote = indexes.get(code);
            if (quote != null && quote.getAmountWan() != null) {
                total = total.add(quote.getAmountWan());
                found++;
            }
        }
        if (found < 2) {
            return null;
        }
        return total.divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);
    }

    /**
     * 指定交易日的两市成交额（亿元），走腾讯日 K。
     * 实时快照只给最新一天，历史日期只能从这里回溯。
     */
    public BigDecimal twoMarketDayAmountBillion(LocalDate date) {
        BigDecimal sh = dayAmountBillion(shIndex, date);
        BigDecimal sz = dayAmountBillion(szIndex, date);
        if (sh == null || sz == null) {
            // 缺一半就不给数：只算沪市会静默少掉一万亿，那比没有数据更糟
            return null;
        }
        return sh.add(sz).setScale(2, RoundingMode.HALF_UP);
    }

    /** 单只指数的日成交额（亿元）。 */
    public BigDecimal dayAmountBillion(String symbol, LocalDate date) {
        String url = klineUrl + "?param=" + symbol + ",day," + date + "," + date + "," + dayAmountBars + ",qfq";
        return parseDayAmount(fetcher.getText(url), symbol, date);
    }

    /**
     * 当天的分钟序列。一票一次请求（这个接口不接多代码），所以调用方是一趟循环。
     *
     * <p><strong>只有当天</strong>：实测 2026-10-28 请求返回 {@code date=20261028}、267 根、
     * 首根 0930、末根 1530。过完今晚这一天的序列就永久取不到了，所以它必须由排班每天抓，
     * 而不能等到要用的时候再回源——这一条就是 {@code stockMinutePullTask} 存在的全部理由。
     *
     * <p>每根是「HHMM 价格 累计量(手) 累计额(元)」，累计口径原样交出，差分留给读侧。
     * 空响应／结构不对／date 缺失一律返回空序列（{@link HttpFetcher} 的约定：失败收敛成 null，
     * 一只票拉挂不该让整趟循环停摆），由调用方决定在报文里点名。
     */
    public MinuteSeries minuteSeries(String gtimgCode) {
        if (gtimgCode == null || gtimgCode.isEmpty()) {
            return MinuteSeries.empty();
        }
        return parseMinuteSeries(fetcher.getText(minuteUrl + "?code=" + gtimgCode), gtimgCode);
    }

    /** 包级可见：单测直接喂存下来的响应，不联网。 */
    MinuteSeries parseMinuteSeries(String body, String symbol) {
        if (body == null || body.trim().isEmpty()) {
            return MinuteSeries.empty();
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode payload = root.path("data").path(symbol).path("data");
            JsonNode rows = payload.path("data");
            LocalDate date = toDecimalDate(payload.path("date").asText(null));
            if (!rows.isArray() || rows.size() == 0) {
                return MinuteSeries.empty();
            }
            List<MinuteTick> ticks = new ArrayList<MinuteTick>();
            for (JsonNode row : rows) {
                MinuteTick tick = parseTick(row.asText(null), date);
                if (tick != null) {
                    ticks.add(tick);
                }
            }
            return new MinuteSeries(date, ticks);
        } catch (Exception e) {
            log.warn("分钟序列解析失败 symbol={} 原因={}", symbol, e.getClass().getSimpleName());
            return MinuteSeries.empty();
        }
    }

    /**
     * 一根：{@code "0930 10.74 42064 45176736.00"}。
     *
     * <p>时间或价格解不出来就整根丢弃而不是补 0：一根假的 0 价会画成断崖，
     * 而少一根只是那一分钟没采样，读侧差分照样连贯。
     */
    static MinuteTick parseTick(String raw, LocalDate date) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        Integer minute = toMinute(parts[0]);
        BigDecimal price = toDecimal(parts[1]);
        if (minute == null || price == null) {
            return null;
        }
        return new MinuteTick(minute, price,
                parts.length > 2 ? toLong(parts[2]) : null,
                parts.length > 3 ? toDecimal(parts[3]) : null);
    }

    /** {@code "0930"}（也可能带秒 {@code "093000"}）→ 930。 */
    static Integer toMinute(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.trim().replaceAll("[^0-9]", "");
        if (digits.length() == 6) {
            digits = digits.substring(0, 4);
        }
        if (digits.length() != 4) {
            return null;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code "20261028"} → LocalDate；解不出来给 null，让调用方去判「这不是今天的序列」。 */
    static LocalDate toDecimalDate(String raw) {
        return toDate(raw);
    }

    /** 一天的分钟序列：日期 ＋ 逐根。空响应用 {@link #empty()}。 */
    public static class MinuteSeries {
        private final LocalDate date;
        private final List<MinuteTick> ticks;

        public MinuteSeries(LocalDate date, List<MinuteTick> ticks) {
            this.date = date;
            this.ticks = ticks == null ? new ArrayList<MinuteTick>() : ticks;
        }

        public static MinuteSeries empty() {
            return new MinuteSeries(null, new ArrayList<MinuteTick>());
        }

        public LocalDate getDate() {
            return date;
        }

        public List<MinuteTick> getTicks() {
            return ticks;
        }

        public boolean isEmpty() {
            return ticks.isEmpty();
        }
    }

    /** 一根分钟线。量与额都是累计值。 */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class MinuteTick {
        private final Integer minute;
        private final BigDecimal price;
        private final Long cumVolumeHand;
        private final BigDecimal cumAmountYuan;
    }

    /**
     * 一段日 K（前复权），按日期升序。阵眼跨度、监管期数交易日都读它。
     *
     * <p>上游只认「截至 end 的最近 N 根」，start 参数会被忽略，所以要超量请求再自己截断；
     * 而窗口第一天也要有涨跌幅，就必须把它前一根一起拉进来。
     * 多花的只是这一只票本就该拉的那一次请求，一只票一次覆盖整个窗口。
     */
    public List<DayBar> dailyBars(String gtimgCode, LocalDate start, LocalDate end) {
        long calendarDays = ChronoUnit.DAYS.between(start, end);
        int bars = (int) Math.max(dayAmountBars, calendarDays * 2 + 15);
        String url = klineUrl + "?param=" + gtimgCode + ",day," + start + "," + end + "," + bars + ",qfq";
        return parseDailyBars(fetcher.getText(url), gtimgCode, start, end);
    }

    /**
     * 包级可见，便于用 fixture 离线单测。
     * 涨幅一律由「前一根收盘」推出来：前复权价做不了绝对价比较，
     * 实测真实 +10.00% 的涨停在 qfq 序列里会算成 +10.13%，所以涨跌停判定只能走涨幅阈值。
     */
    List<DayBar> parseDailyBars(String body, String symbol, LocalDate start, LocalDate end) {
        List<DayBar> bars = new ArrayList<>();
        if (body == null) {
            return bars;
        }
        try {
            JsonNode node = mapper.readTree(body).path("data").path(symbol);
            JsonNode rows = node.path("day");
            if (!rows.isArray()) {
                rows = node.path("qfqday");
            }
            // 复权版本号是 symbol 级的，一段里所有行共用同一个值
            String version = node.path("version").asText(null);
            BigDecimal prevClose = null;
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() <= IDX_KLINE_LOW) {
                    continue;
                }
                String rawDate = row.get(0).asText();
                LocalDate date;
                try {
                    date = LocalDate.parse(rawDate);
                } catch (RuntimeException e) {
                    continue;   // 一行坏日期只丢这一行，不能把整窗丢掉
                }
                DayBar bar = new DayBar();
                bar.setDate(date);
                bar.setFqVersion(version);
                bar.setOpen(toDecimal(row.get(1).asText()));
                bar.setClose(toDecimal(row.get(2).asText()));
                bar.setHigh(toDecimal(row.get(3).asText()));
                bar.setLow(toDecimal(row.get(4).asText()));
                if (prevClose != null && bar.getClose() != null && prevClose.signum() > 0) {
                    bar.setPct(percentOf(bar.getClose(), prevClose));
                    // 盘中触板看的是最低价相对前收，不是收盘价
                    if (bar.getLow() != null) {
                        bar.setLowPct(percentOf(bar.getLow(), prevClose));
                    }
                }
                if (bar.getClose() != null) {
                    prevClose = bar.getClose();
                }
                if (!date.isBefore(start) && !date.isAfter(end)) {
                    bars.add(bar);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("日K解析失败 symbol={} 原因={}", symbol, e.getClass().getSimpleName());
            return new ArrayList<>();
        }
        return bars;
    }

    private static BigDecimal percentOf(BigDecimal value, BigDecimal prevClose) {
        return value.subtract(prevClose).multiply(HUNDRED)
                .divide(prevClose, 2, RoundingMode.HALF_UP);
    }

    /** 一根日 K。停牌日根本不会出现在序列里，所以数交易日只要数这个列表。 */
    @lombok.Data
    public static class DayBar {
        private LocalDate date;
        private BigDecimal open;
        private BigDecimal close;
        private BigDecimal high;
        private BigDecimal low;
        /** 收盘涨幅 %，由前一根收盘推出；序列里没有前一根时为 null。 */
        private BigDecimal pct;
        /** 盘中最低涨幅 %，用于判「触板没触板」。 */
        private BigDecimal lowPct;
        /**
         * 上游这次返回所用的复权版本号（{@code data.version}）。
         *
         * <p>前复权价不是静态值：分红送股会让上游把历史整段重算，而这个号就是重算后的版本。
         * 落库时随行存下来，读一段时用它判断"这几根是不是同一批复权基准下算出来的"——
         * 一段里出现两种号说明是不同时间拉的段拼在一起的，衔接处会有一次凭空的假跳变。
         */
        private String fqVersion;
    }

    /** 包级可见，便于用 fixture 离线单测。 */
    BigDecimal parseDayAmount(String body, String symbol, LocalDate date) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(body).path("data").path(symbol);
            JsonNode rows = node.path("day");
            if (!rows.isArray()) {
                rows = node.path("qfqday");
            }
            String day = date.toString();
            for (JsonNode row : rows) {
                if (row.isArray() && row.size() > IDX_KLINE_AMOUNT && day.equals(row.get(0).asText())) {
                    BigDecimal wan = toDecimal(row.get(IDX_KLINE_AMOUNT).asText());
                    return wan == null ? null : wan.divide(WAN, 2, RoundingMode.HALF_UP);
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            log.warn("日K解析失败 symbol={} 原因={}", symbol, e.getClass().getSimpleName());
            return null;
        }
    }

    private static BigDecimal toDecimal(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 量都是「手」这种整数，取不出来就交 null，别让一个坏字段把整只票的快照丢掉。 */
    private static Long toLong(String raw) {
        BigDecimal dec = toDecimal(raw);
        return dec == null ? null : dec.longValue();
    }

    private static LocalDate toDate(String raw) {
        if (raw == null || raw.trim().length() < 8) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim().substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 只保留取数真正要用的字段。 */
    @lombok.Data
    public static class StockQuote {
        /** 带市场前缀的代码，如 sh000001 / sz002909。 */
        private String symbol;
        private String code;
        private String name;
        private BigDecimal changePct;
        private BigDecimal prevClose;
        private BigDecimal amountWan;
        private LocalDate quoteDate;

        // ---- 竞价快照用的几段（溢价/成交额那套老调用方不读，留着为 null 无碍）----
        /** 现价：09:26 抓到就是竞价成交价（= 当天开盘价）。 */
        private BigDecimal lastPrice;
        private BigDecimal openPrice;
        /** 累计成交量(手)：09:30 前只有竞价，故它就是竞价量。 */
        private Long volumeHand;
        private BigDecimal bid1Price;
        private Long bid1Hand;
        private BigDecimal ask1Price;
        private Long ask1Hand;
        /** 当日涨停价，用来判「竞价即涨停」。 */
        private BigDecimal limitUpPrice;
        /** 第 30 段原文（yyyyMMddHHmmss），落库只信 capturedAt，这个用来核对。 */
        private String quoteTime;
        /** 整行 ~ 载荷原文，字段解释变了靠它重算。 */
        private String rawPayload;
    }

    /** 池子里的行 → 腾讯代码。quotes() 返回的 map 以此值为 key，反查也用它。 */
    public static String toGtimgCode(PoolRow row) {
        if (row.getCode() == null || row.getMarket() == null) {
            return null;
        }
        String code = row.getCode();
        // 取不到前缀就少一个样本，样本数会如实上报
        String prefix = row.getMarket() == 1 ? "sh" : (isBeijing(code) ? "bj" : "sz");
        return prefix + code;
    }

    /** 4/8/9 开头是北交所（920xxx 是新段）——日幅度 30%，其余板块 20%。 */
    public static boolean isBeijing(String code) {
        return code != null && (code.startsWith("4") || code.startsWith("8") || code.startsWith("9"));
    }

    /**
     * 只有裸代码时的前缀判据：6 沪、4/8/9 北、其余深。
     *
     * 监管表里存的就是裸代码（东财公告接口不给市场标识），而这些代码都在
     * {@code t_stock} 的白名单段位里，按首位判不会串市场。
     */
    public static String symbolOf(String code) {
        if (code == null || code.length() != 6) {
            return null;
        }
        char head = code.charAt(0);
        if (head == '6') {
            return "sh" + code;
        }
        return isBeijing(code) ? "bj" + code : "sz" + code;
    }

    public static List<String> toGtimgCodes(List<PoolRow> rows) {
        List<String> codes = new ArrayList<>();
        for (PoolRow row : rows) {
            String code = toGtimgCode(row);
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }
}
