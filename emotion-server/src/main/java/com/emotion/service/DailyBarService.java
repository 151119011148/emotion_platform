package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.emotion.entity.DailyBar;
import com.emotion.entity.DailyBarFetch;
import com.emotion.mapper.DailyBarFetchMapper;
import com.emotion.mapper.DailyBarMapper;
import com.emotion.market.MarketDataException;
import com.emotion.market.TencentClient;
import com.emotion.market.TencentClient.DayBar;

/**
 * 日 K 的唯一取数入口：历史段走库，近日段回源，拉到的一律落库。
 *
 * <p>为什么值得落库：日 K 是「过去的事实」，昨天那根收盘不会因为多看一眼就变。
 * 但改它之前，全站每一处日 K（阵眼跨度、监管异动追踪、D5 阵眼当日涨幅、/market/daily-bars）
 * 都是直接打上游，一只票一次网络往返，同一个窗口每开一次页面就重打一遍。
 *
 * <p>三件不能做错的事：
 * <ol>
 *   <li><b>命中判定只看拉取留痕，不看行数。</b>停牌日天然没有行而且是永久状态，
 *       用「行数够不够交易日数」判命中，停牌股会永远判成未缓存，缓存等于没做。
 *       所以 {@code t_daily_bar_fetch} 是本服务成立的前提。</li>
 *   <li><b>前复权不是真静态。</b>一旦除权除息，上游把历史 qfq 价整体重算，库里旧值就悄悄变错。
 *       应对是给拉取记录加保鲜期（{@code max-age-days}），过期重拉一次顺带吸收除权。
 *       所以这是<b>带保鲜期的缓存</b>，不是档案表。</li>
 *   <li><b>近日不信任库。</b>腾讯个股日线当天滞后若干小时（收盘后拉 sz000993 最新只到 T-3），
 *       缓存一个还没长出来的数据只会把它钉死。{@code volatile-days} 内的窗口一律回源。</li>
 * </ol>
 *
 * <p>涨跌幅一律现算：只存四价，pct / lowPct 由「序列里紧邻的前一根收盘」推出。
 * 落库派生值就等于让同一个数有两处真相——除权重算后旧 pct 不会跟着变，错得更隐蔽。
 */
@Service
public class DailyBarService {

    private static final Logger log = LoggerFactory.getLogger(DailyBarService.class);

    /** 所有"今天"以东八区为准，JVM 默认时区在别的机器上会算错交易日。 */
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int BATCH = 500;

    private final TencentClient tencent;
    private final DailyBarMapper barMapper;
    private final DailyBarFetchMapper fetchMapper;
    private final boolean enabled;
    /** 落库时往前多要的自然日：窗口首日也要有前收可比，否则首日涨幅恒为 null。 */
    private final int leadDays;
    /** 最近这几个自然日视为易变（数据还没长全），不信任库、每次回源。 */
    private final int volatileDays;
    /** 拉取记录的保鲜天数：过期重拉，用来吸收除权导致的 qfq 重算。 */
    private final int maxAgeDays;
    /** 易变段的进程内短缓存，避免刷新一下页面就把同一只票打一遍上游。 */
    private final long volatileTtlMs;

    private final ConcurrentHashMap<String, HotEntry> hot = new ConcurrentHashMap<>();

    public DailyBarService(TencentClient tencent,
                           DailyBarMapper barMapper,
                           DailyBarFetchMapper fetchMapper,
                           @Value("${market.daily-bar.enabled:true}") boolean enabled,
                           @Value("${market.daily-bar.lead-days:20}") int leadDays,
                           @Value("${market.daily-bar.volatile-days:3}") int volatileDays,
                           @Value("${market.daily-bar.max-age-days:30}") int maxAgeDays,
                           @Value("${market.daily-bar.volatile-ttl-ms:120000}") long volatileTtlMs) {
        this.tencent = tencent;
        this.barMapper = barMapper;
        this.fetchMapper = fetchMapper;
        this.enabled = enabled;
        this.leadDays = leadDays;
        this.volatileDays = volatileDays;
        this.maxAgeDays = maxAgeDays;
        this.volatileTtlMs = volatileTtlMs;
    }

    /** 一段日 K，按日期升序，含首尾。 */
    public List<DayBar> bars(String symbol, LocalDate start, LocalDate end) {
        return bars(symbol, start, end, false);
    }

    /**
     * 一段日 K。{@code forceRefresh=true} 时跳过库直接回源（发现数据不对时的手动重拉入口）。
     *
     * <p>返回的是窗口内的行，但内部按「窗口 + 前置 lead 段」取数，
     * 这样窗口第一天的涨幅也是拿真前收算的，不会凭空少一个点。
     */
    public List<DayBar> bars(String symbol, LocalDate start, LocalDate end, boolean forceRefresh) {
        if (symbol == null || symbol.trim().isEmpty() || start == null || end == null) {
            throw new MarketDataException("日 K 需要 symbol、start、end 三个参数");
        }
        String sym = symbol.trim();
        if (!enabled) {
            return tencent.dailyBars(sym, start, end);
        }
        LocalDate from = start.minusDays(leadDays);
        boolean hotWindow = isVolatile(end);

        if (hotWindow && !forceRefresh && volatileTtlMs > 0) {
            HotEntry hit = hot.get(hotKey(sym, from, end));
            if (hit != null && !hit.expired()) {
                return trim(hit.bars, start, end);
            }
        }
        if (!hotWindow && !forceRefresh) {
            DailyBarFetch cover = findCover(sym, from, end);
            if (cover != null) {
                List<DayBar> cached = readDb(sym, from, end);
                // 空结果不认命中：留痕在但行没写进去（写过库失败），这种情况必须回源，
                // 否则一次写库失败会让这只票在这个区间永久返回空。
                if (!cached.isEmpty()) {
                    return trim(cached, start, end);
                }
            }
        }
        return fetchAndStore(sym, from, end, start, hotWindow);
    }

    /** 清掉一只票的拉取留痕与短缓存，下次查询必然回源。日 K 行留着，回源会覆盖写。 */
    public int evict(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return 0;
        }
        String sym = symbol.trim();
        for (String key : new ArrayList<>(hot.keySet())) {
            if (key.startsWith(sym + "|")) {
                hot.remove(key);
            }
        }
        try {
            return fetchMapper.deleteBySymbol(sym);
        } catch (RuntimeException e) {
            log.warn("清理日K拉取留痕失败 symbol={}：{}", sym, e.getMessage());
            return 0;
        }
    }

    // ---------------- 内部 ----------------

    private List<DayBar> fetchAndStore(String sym, LocalDate from, LocalDate end,
                                       LocalDate start, boolean hotWindow) {
        List<DayBar> fresh = tencent.dailyBars(sym, from, end);
        if (fresh == null) {
            fresh = new ArrayList<>();
        }
        if (!fresh.isEmpty()) {
            // 回源是唯一能看见"上游现在怎么说"的时刻，除权检测只能挂在这里：
            // 命中路径根本不打上游，不可能自己发现历史价被重算过。
            dropStaleOnRestatement(sym, fresh);
            store(sym, fresh, hotWindow, from, end);
        }
        if (hotWindow && volatileTtlMs > 0) {
            hot.put(hotKey(sym, from, end), new HotEntry(fresh, volatileTtlMs));
        }
        return trim(fresh, start, end);
    }

    /**
     * 除权自愈：新拉回的序列与库里同日的收盘价对不上，就是上游把历史价重算了（分红送股）。
     *
     * <p>此时库里所有旧行都不可信——不同批次的复权因子拼在一起，衔接处会凭空出现一次跳变，
     * 而这段错得不声不响。所以整只票作废，让后续查询自然回源重写，而不是只覆盖本次区间：
     * 只覆盖本次的话，没被这次区间碰到的旧段仍带着旧因子，下次读到又是一场混拼。
     */
    private void dropStaleOnRestatement(String sym, List<DayBar> fresh) {
        List<DailyBar> old;
        try {
            old = barMapper.selectRange(sym, fresh.get(0).getDate(), fresh.get(fresh.size() - 1).getDate());
        } catch (RuntimeException e) {
            return;   // 库读不了就当没旧数据，后面照常写
        }
        if (old == null || old.isEmpty()) {
            return;
        }
        Map<LocalDate, BigDecimal> byDate = new HashMap<>();
        for (DailyBar row : old) {
            byDate.put(row.getTradeDate(), row.getClosePrice());
        }
        LocalDate clash = null;
        BigDecimal stored = null;
        BigDecimal upstream = null;
        for (DayBar bar : fresh) {
            BigDecimal have = byDate.get(bar.getDate());
            if (have != null && bar.getClose() != null && have.compareTo(bar.getClose()) != 0) {
                clash = bar.getDate();
                stored = have;
                upstream = bar.getClose();
                break;
            }
        }
        if (clash == null) {
            return;
        }
        log.warn("日K复权重算 symbol={} 冲突日={} 库里={} 上游={}：作废这只票的全部缓存行与留痕",
                sym, clash, stored, upstream);
        try {
            barMapper.deleteBySymbol(sym);
            fetchMapper.deleteBySymbol(sym);
        } catch (RuntimeException e) {
            log.warn("作废日K缓存失败 symbol={}：{}", sym, e.getMessage());
        }
    }

    /** 写库失败只记日志：缓存写不进去不该让页面出不来数，回源的那一手已经拿到了。 */
    private void store(String sym, List<DayBar> bars, boolean hotWindow, LocalDate from, LocalDate end) {
        List<DailyBar> rows = new ArrayList<>(bars.size());
        for (DayBar bar : bars) {
            if (bar.getDate() == null || bar.getClose() == null) {
                continue;   // 没有收盘的行存下来也没有意义，反而会污染"这段有数据"的判断
            }
            DailyBar row = new DailyBar();
            row.setSymbol(sym);
            row.setTradeDate(bar.getDate());
            row.setOpenPrice(bar.getOpen());
            row.setClosePrice(bar.getClose());
            row.setHighPrice(bar.getHigh());
            row.setLowPrice(bar.getLow());
            row.setFqVersion(bar.getFqVersion());
            rows.add(row);
        }
        try {
            for (int i = 0; i < rows.size(); i += BATCH) {
                barMapper.upsertBatch(new ArrayList<>(rows.subList(i, Math.min(i + BATCH, rows.size()))));
            }
            // 易变段不留痕：这段数据还没长全，留了痕等它变历史时会被直接命中，
            // 于是"盘中拉过一次"就钉死了那天的最终值。宁可到时候多回源一次。
            if (!hotWindow) {
                DailyBarFetch mark = new DailyBarFetch();
                mark.setSymbol(sym);
                mark.setStartDate(from);
                mark.setEndDate(end);
                mark.setBarCount(rows.size());
                mark.setFqVersion(bars.isEmpty() ? null : bars.get(0).getFqVersion());
                mark.setFetchedAt(LocalDateTime.now(CN));
                fetchMapper.upsert(mark);
            }
        } catch (RuntimeException e) {
            log.warn("日K落库失败 symbol={} 区间=[{},{}] 原因={}", sym, from, end, e.getMessage());
        }
    }

    private DailyBarFetch findCover(String sym, LocalDate from, LocalDate end) {
        LocalDateTime notBefore = LocalDateTime.now(CN).minusDays(maxAgeDays);
        try {
            return fetchMapper.findCovering(sym, from, end, notBefore);
        } catch (RuntimeException e) {
            log.warn("查询日K拉取留痕失败 symbol={}：{}", sym, e.getMessage());
            return null;
        }
    }

    /** 库里读一段并重算涨跌幅：口径与上游一致，都是「序列里紧邻的前一根收盘」。 */
    private List<DayBar> readDb(String sym, LocalDate from, LocalDate end) {
        List<DailyBar> rows;
        try {
            rows = barMapper.selectRange(sym, from, end);
        } catch (RuntimeException e) {
            log.warn("读取日K缓存失败 symbol={}：{}", sym, e.getMessage());
            return new ArrayList<>();
        }
        List<DayBar> bars = new ArrayList<>();
        if (rows == null) {
            return bars;
        }
        // 段内复权版本号必须唯一。出现两种说明这段是不同时间拉的段拼起来的——
        // 两边因子不同，衔接那天会凭空算出一次假跳变，而它看起来和真行情一模一样。
        // 判为未命中，让调用方整段重拉（空结果在上层就是"没缓存"）。
        Set<String> versions = new HashSet<>();
        for (DailyBar row : rows) {
            if (row.getFqVersion() != null) {
                versions.add(row.getFqVersion());
            }
        }
        if (versions.size() > 1) {
            log.warn("日K缓存段内复权版本混杂 symbol={} 版本={}：判为未命中并整段重拉", sym, versions);
            return bars;
        }
        BigDecimal prevClose = null;
        for (DailyBar row : rows) {
            DayBar bar = new DayBar();
            bar.setDate(row.getTradeDate());
            bar.setOpen(row.getOpenPrice());
            bar.setClose(row.getClosePrice());
            bar.setHigh(row.getHighPrice());
            bar.setLow(row.getLowPrice());
            if (prevClose != null && bar.getClose() != null && prevClose.signum() > 0) {
                bar.setPct(percentOf(bar.getClose(), prevClose));
                if (bar.getLow() != null) {
                    bar.setLowPct(percentOf(bar.getLow(), prevClose));
                }
            }
            if (bar.getClose() != null) {
                prevClose = bar.getClose();
            }
            bars.add(bar);
        }
        return bars;
    }

    private static List<DayBar> trim(List<DayBar> bars, LocalDate start, LocalDate end) {
        List<DayBar> out = new ArrayList<>(bars.size());
        for (DayBar bar : bars) {
            LocalDate day = bar.getDate();
            if (day != null && !day.isBefore(start) && !day.isAfter(end)) {
                out.add(bar);
            }
        }
        return out;
    }

    /** 窗口末端落在最近 volatileDays 个自然日里就算易变：数据可能还没长全，不信任库。 */
    private boolean isVolatile(LocalDate end) {
        return !end.isBefore(LocalDate.now(CN).minusDays(volatileDays));
    }

    private static String hotKey(String sym, LocalDate from, LocalDate end) {
        return sym + "|" + from + "|" + end;
    }

    private static BigDecimal percentOf(BigDecimal value, BigDecimal prevClose) {
        return value.subtract(prevClose).multiply(HUNDRED)
                .divide(prevClose, 2, RoundingMode.HALF_UP);
    }

    /** 易变段的短缓存条目。存的是含 lead 段的完整序列，取用时再截窗口。 */
    private static class HotEntry {
        private final List<DayBar> bars;
        private final long expireAt;

        HotEntry(List<DayBar> bars, long ttlMs) {
            this.bars = bars;
            this.expireAt = System.currentTimeMillis() + ttlMs;
        }

        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    /** 给排查用：这只票被拉过哪些区间。 */
    public List<DailyBarFetch> fetchLog(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return fetchMapper.listBySymbol(symbol.trim());
        } catch (RuntimeException e) {
            log.warn("查询日K拉取留痕失败 symbol={}：{}", symbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 当前是否在用库缓存（配置关掉时为 false，排查"为什么还在打上游"用得上）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 缓存的配置口径，排查用。 */
    public Map<String, Object> config() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("leadDays", leadDays);
        m.put("volatileDays", volatileDays);
        m.put("maxAgeDays", maxAgeDays);
        m.put("volatileTtlMs", volatileTtlMs);
        m.put("hotEntries", hot.size());
        return m;
    }
}
