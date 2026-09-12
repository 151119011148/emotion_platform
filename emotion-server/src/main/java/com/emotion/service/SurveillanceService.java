package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.entity.Surveillance;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.SurveillanceMapper;
import com.emotion.market.EastmoneyClient;
import com.emotion.market.MarketDataException;
import com.emotion.market.MarketMetrics;
import com.emotion.market.SurveillanceKind;
import com.emotion.market.SurveillanceNotice;
import com.emotion.market.SurveillanceResult;
import com.emotion.market.SurvivalMember;
import com.emotion.market.TencentClient;
import com.emotion.market.TencentClient.DayBar;
import com.emotion.market.TencentClient.StockQuote;
import com.emotion.vo.SurveillanceVO;

/**
 * 异动监管期：拉公告事件、按该票自己的交易日序列推"今天还在列吗"。
 *
 * 四条设计决定：
 * 1. 表里只存事件，不存状态。监管期是查的时候数出来的（{@link #dayIndexAfter}），
 *    所以 {@link SurveillanceKind} 上的 3/10 日窗口改了不用回补。
 * 2. 只查跟踪中的票。全市场扫一天要 8 个请求且服务端过滤参数一律不可信，
 *    而跟踪集合（近期非首板涨停池 ∪ 表里已有的代码）实测一百来只，一只一个请求能覆盖整个窗口。
 * 3. 拉取按"一只一次请求"组织，交给脚本并发。服务端这里不并线程：一次 100 只的刷新
 *    会在一个 HTTP 请求里打 100 次上游，会顶穿 {@code market.budget-ms}。
 * 4. 反过来，读某一天的名单必须并发：在列人数跟着 ZD 的例行公告走，实测一天最多 43 只，
 *    逐只串着拉日 K 是十几秒。所以读路径用一道固定线程数的小池子（{@link #BAR_THREADS}），
 *    拉不到的那只涨幅留 null，表现为 matched &lt; count，不会伪装成 0%。
 */
@Service
public class SurveillanceService {

    private static final Logger log = LoggerFactory.getLogger(SurveillanceService.class);

    /** 最长窗口 10 个交易日，往前 45 个自然日足够盖住长假。 */
    private static final int LOOKBACK_DAYS = 45;
    /** 一次多行写：与 {@link StockPoolWriter} 同一口径，太大顶到 max_allowed_packet。 */
    private static final int CHUNK = 400;
    /** 读某一天的名单时的并发度：与回补脚本同一档，再高对上游没好处。 */
    private static final int BAR_THREADS = 6;
    /** 整批读取的上限。单次 HTTP 本身有超时，这道是防止名单大到把请求挂住。 */
    private static final int BAR_FETCH_TIMEOUT_SECONDS = 30;
    /** 所有"今天"都必须以东八区为准。 */
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final EastmoneyClient eastmoney;
    private final TencentClient tencent;
    private final SurveillanceMapper surveillanceMapper;
    private final MarketStockMapper marketStockMapper;
    /** 只服务读路径：某天的在列名单逐只拉日 K，串行是十几秒。 */
    private final ExecutorService barFetch = Executors.newFixedThreadPool(BAR_THREADS);
    /** 事件写串行用这一把锁；读与上游拉取不受它影响。 */
    private final Object writeLock = new Object();

    public SurveillanceService(EastmoneyClient eastmoney,
                              TencentClient tencent,
                              SurveillanceMapper surveillanceMapper,
                              MarketStockMapper marketStockMapper) {
        this.eastmoney = eastmoney;
        this.tencent = tencent;
        this.surveillanceMapper = surveillanceMapper;
        this.marketStockMapper = marketStockMapper;
    }

    // ---------- 拉取 ----------

    /**
     * 查一只票一个窗口的公告并幂等落库。
     *
     * @return 上游失败时 ok=false——调用方必须据此重试或告警，不能把它当成"这只票没被监管"
     */
    public RefreshOutcome refreshOne(String code, LocalDate begin, LocalDate end) {
        SurveillanceResult result = eastmoney.surveillance(code, begin, end);
        if (!result.isOk()) {
            return RefreshOutcome.failed(code, result.getReason());
        }
        int written = upsert(result.getNotices());
        if (result.getUnmatchedSignals() > 0) {
            log.warn("{} 有 {} 条标题像异动/监管却没命中类目码，上游类目可能改过：{}~{}",
                    code, result.getUnmatchedSignals(), begin, end);
        }
        return RefreshOutcome.ok(code, result.getRows(), result.getNotices().size(), written,
                result.getUnmatchedSignals());
    }

    /** 逐只顺序刷新：给"每天那点增量"用（跟踪集合里今天新入的 + 昨天仍在列的）。 */
    public List<RefreshOutcome> refresh(Collection<String> codes, LocalDate begin, LocalDate end) {
        List<RefreshOutcome> outcomes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String code : codes) {
            if (code == null || code.trim().isEmpty() || !seen.add(code.trim())) {
                continue;
            }
            outcomes.add(refreshOne(code.trim(), begin, end));
        }
        log.info("监管公告刷新 {} 只：成功 {}、失败 {}，落库事件 {} 条",
                outcomes.size(), countSucceeded(outcomes), countFailed(outcomes), sumWritten(outcomes));
        return outcomes;
    }

    /**
     * 跟踪集合：窗口内非首板涨停池成员 ∪ 表里已有事件的代码。
     *
     * 为什么含"表里已有的代码"：一只票的监管期靠的是它几周前的公告，而它今天可能早已不在涨停池里
     * （哈药 08-21 是收盘跌停，压根没进那天的涨停池）。少了这一半，正在监管期中的票就再也不会被重查，
     * 多日升级链（实测龙版传媒 09-02→09-03→09-04 连着三起）会在中途断掉。
     */
    public List<String> trackedCodes(LocalDate begin, LocalDate end) {
        Set<String> codes = new TreeSet<>();
        List<MarketStock> rows = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP)
                .ge(MarketStock::getTradeDate, begin)
                .le(MarketStock::getTradeDate, end)
                .ge(MarketStock::getConsecutive, 2));
        for (MarketStock row : rows) {
            if (row.getCode() != null) {
                codes.add(row.getCode());
            }
        }
        for (Surveillance event : surveillanceMapper.selectList(
                new LambdaQueryWrapper<Surveillance>().select(Surveillance::getStockCode))) {
            codes.add(event.getStockCode());
        }
        return new ArrayList<>(codes);
    }

    @Transactional(rollbackFor = Exception.class)
    public int upsert(List<SurveillanceNotice> notices) {
        List<Surveillance> rows = new ArrayList<>();
        for (SurveillanceNotice notice : notices) {
            if (notice.getCode() == null || notice.getArtCode() == null || notice.getKind() == null) {
                continue;
            }
            Surveillance row = new Surveillance();
            row.setStockCode(notice.getCode());
            // stock_name NOT NULL：上游偶尔不给 short_name，宁可留代码也不能让整批写失败
            row.setStockName(notice.getName() == null ? notice.getCode() : notice.getName());
            row.setAnnDate(notice.getAnnDate());
            row.setKind(notice.getKind().name());
            row.setTitle(cut(notice.getTitle(), 200));
            row.setColumnCode(cut(notice.getColumnCode(), 24));
            row.setArtCode(notice.getArtCode());
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return 0;
        }
        // 上游拉取可以并发，写库不行：六路 INSERT ... ON DUPLICATE KEY UPDATE 会在 uk_code_art 上互相
        // 等到成环（120 只回补实测撞过一次）。提交在锁外，所以最坏只是下一个写者短等一下，不会再成环。
        synchronized (writeLock) {
            for (int from = 0; from < rows.size(); from += CHUNK) {
                surveillanceMapper.upsertBatch(rows.subList(from, Math.min(from + CHUNK, rows.size())));
            }
        }
        return rows.size();
    }

    // ---------- 读：某日的在列名单 ----------

    /**
     * 往前 LOOKBACK_DAYS 天、公告日严格早于 date 的事件行数——和 {@link #onList} 同一组边界。
     *
     * <p>这是第 9 维"0 家"与"从没拉过"的判据来源：一行都没有，说明这天的公告根本没回补过，
     * 那是缺数据（整维未评），不是"当天确认没有在列的票"（中性事实）。
     */
    public int eventsInWindow(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("eventsInWindow 需要 date");
        }
        Long count = surveillanceMapper.selectCount(new LambdaQueryWrapper<Surveillance>()
                .lt(Surveillance::getAnnDate, date)
                .ge(Surveillance::getAnnDate, date.minusDays(LOOKBACK_DAYS)));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 某日在列的监管股。
     *
     * @param scope  限定代码，null=表里当天有事件的全部代码（阵眼与涨停池之外不额外花钱）
     * @param daysOf 该票的交易日序列。回补路径传它自己的日 K（停牌自然不消耗窗口）；
     *               当日路径传指数日 K（一只票一次请求都覆盖）——名单只有一份实现，换的是输入
     */
    public List<SurvivalMember> onList(Collection<String> scope, LocalDate date,
                                       Function<String, List<LocalDate>> daysOf) {
        if (date == null) {
            throw new IllegalArgumentException("onList 需要 date");
        }
        List<Surveillance> events = surveillanceMapper.selectList(
                new LambdaQueryWrapper<Surveillance>()
                        .lt(Surveillance::getAnnDate, date)
                        .ge(Surveillance::getAnnDate, date.minusDays(LOOKBACK_DAYS)));
        Map<String, List<Surveillance>> byCode = new TreeMap<>();
        for (Surveillance event : events) {
            if (scope != null && !scope.contains(event.getStockCode())) {
                continue;
            }
            byCode.computeIfAbsent(event.getStockCode(), key -> new ArrayList<>()).add(event);
        }

        List<SurvivalMember> members = new ArrayList<>();
        for (Map.Entry<String, List<Surveillance>> entry : byCode.entrySet()) {
            List<LocalDate> days = daysOf.apply(entry.getKey());
            if (days == null || days.isEmpty()) {
                // 数不了交易日就等于不知道它在不在列——跳过并喊出来，不能悄悄当成"不在列"
                log.warn("{} 没有交易日序列，{} 的监管期无法判定，已从名单里跳过", entry.getKey(), date);
                continue;
            }
            SurvivalMember member = memberOf(entry.getValue(), date, days);
            if (member != null) {
                members.add(member);
            }
        }
        return members;
    }

    /**
     * 某日的在列名单，连当日涨跌一起给。
     *
     * 请求数是这里唯一的硬约束：名单用一次指数日 K 判（候选代码有一百来只，逐只拉日 K 只为数交易日
     * 会把一次读变成一百多次），只有真的在列的那些才逐只求涨幅。14 天全量回补后在列实测 9~43 只
     * ——ZD 是 2 连板以上的例行公告，量就是这么大——所以那一步走并发线程池。
     * 今天那条路径更省：一次批量报价就够。
     *
     * 代价写在明处：停牌票按公共日历数交易日，可能比它自己的序列早出窗一两天。
     * 真在列的票停牌时涨幅会是 null，表现为 matched &lt; count，不会伪装成一个正常的均值。
     * 要按该票自己的序列算，走 {@link #onList} 把那个序列传进来就行（推导仍然只有一份实现）。
     */
    public List<SurvivalMember> listOn(LocalDate date, Collection<String> scope) {
        if (date == null) {
            throw new IllegalArgumentException("listOn 需要 date");
        }
        if (date.isAfter(LocalDate.now(CN))) {
            // 未来那天还没开盘，日 K 里没有那根柱子，窗口会少数一天、把"在列"报到一个没发生过的日子里。
            throw new MarketDataException("不能查未来日期：" + date + " 还没有开盘，监管期无法判定");
        }
        final LocalDate begin = date.minusDays(LOOKBACK_DAYS);
        final boolean today = date.isEqual(LocalDate.now(CN));
        List<DayBar> calendarBars = fetchBars(calendarCode(), begin, date);
        if (calendarBars.isEmpty()) {
            // 日历空了还能继续数，只是每个窗口都少数好几天——那比报错危险得多
            throw new MarketDataException("交易日序列未取得（" + calendarCode() + " 日 K 为空），"
                    + date + " 的监管期无法判定");
        }
        List<LocalDate> calendar = new ArrayList<>(datesOf(calendarBars));
        if (!calendar.contains(date)) {
            if (!today) {
                throw new MarketDataException(date + " 不在 " + calendarCode() + " 的日 K 序列里，"
                        + "这一天没有交易，监管期无从判起");
            }
            // 盘中指数的当日柱子可能还没生成。今天确实在场（否则不会有这次读取），
            // 少了它每个窗口都会少数一天，哈药就会在周一被当成"第 10 日"继续留在列里。
            calendar.add(date);
        }
        List<SurvivalMember> members = onList(scope, date, code -> calendar);
        if (members.isEmpty()) {
            return members;
        }
        if (today) {
            fillPctFromQuotes(members);
        } else {
            fillPctFromBars(members, begin, date);
        }
        return members;
    }

    /** 公共交易日历用上证指数：系统里没有交易日历表，这是唯一不引入新依赖又能一次拿到的序列。 */
    private String calendarCode() {
        return tencent.shIndexCode();
    }

    private List<DayBar> fetchBars(String symbol, LocalDate begin, LocalDate end) {
        if (symbol == null) {
            return new ArrayList<>();
        }
        List<DayBar> bars = tencent.dailyBars(symbol, begin, end);
        return bars == null ? new ArrayList<>() : bars;
    }

    private static List<LocalDate> datesOf(List<DayBar> bars) {
        List<LocalDate> days = new ArrayList<>(bars.size());
        for (DayBar bar : bars) {
            days.add(bar.getDate());
        }
        return days;
    }

    /** 请求日不在这根柱子上就是没有（停牌），不能拿相邻交易日顶替——同一口径见 TencentClient.parseDayAmount。 */
    private static BigDecimal barPct(List<DayBar> bars, LocalDate date) {
        if (bars == null) {
            return null;
        }
        for (DayBar bar : bars) {
            if (date.equals(bar.getDate())) {
                return bar.getPct();
            }
        }
        return null;
    }

    private void fillPctFromBars(List<SurvivalMember> members, LocalDate begin, LocalDate date) {
        List<Future<BigDecimal>> futures = new ArrayList<>(members.size());
        for (SurvivalMember member : members) {
            futures.add(barFetch.submit(() -> barPct(
                    fetchBars(TencentClient.symbolOf(member.getCode()), begin, date), date)));
        }
        for (int i = 0; i < members.size(); i++) {
            SurvivalMember member = members.get(i);
            try {
                member.setPct(futures.get(i).get(BAR_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                member.setPct(null);
                log.warn("取 {} 的 {} 日涨幅时被中断，这一只按缺价处理", member.getCode(), date);
                return;
            } catch (ExecutionException | TimeoutException e) {
                // 缺的那一只只会让 matched 小于 count，不会把一个取不到的数当成 0%
                member.setPct(null);
                log.warn("取 {} 的 {} 日日K失败，这一只按缺价处理：{}", member.getCode(), date, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void close() {
        barFetch.shutdownNow();
    }

    private void fillPctFromQuotes(List<SurvivalMember> members) {
        List<String> symbols = new ArrayList<>();
        for (SurvivalMember member : members) {
            String symbol = TencentClient.symbolOf(member.getCode());
            if (symbol != null) {
                symbols.add(symbol);
            }
        }
        if (symbols.isEmpty()) {
            return;
        }
        Map<String, StockQuote> quotes = tencent.quotes(symbols);
        if (quotes == null) {
            return;
        }
        for (SurvivalMember member : members) {
            StockQuote quote = quotes.get(TencentClient.symbolOf(member.getCode()));
            member.setPct(quote == null ? null : quote.getChangePct());
        }
    }

    /** 卡片要的那份结果：名单 + 均值 + 人话算式。第 9 维的分数在打分时算，这里不复制一份档位表。 */
    public SurveillanceVO vo(LocalDate requestedDate) {
        LocalDate date = requestedDate != null ? requestedDate : LocalDate.now(CN);
        return voOf(date, listOn(date, null));
    }

    /**
     * 与 {@link #vo} 同一份卡片装配，但名单由调用方传入——D5 融合后同一次在列名单要同时喂
     * 旧第 9 维与高位生态的压制/反馈子项，再拉一次 listOn 就是平白多打十几到三十几次上游。
     */
    public SurveillanceVO voOf(LocalDate date, List<SurvivalMember> members) {
        List<SurvivalMember> list = members == null ? new ArrayList<SurvivalMember>() : members;
        // 均值只算 SEVERE/EXCH：ZD 是 2 连板以上的例行公告，把它算进来这一维就成了涨停池均值本身
        List<SurvivalMember> scored = new ArrayList<>();
        for (SurvivalMember member : list) {
            if (member.scored()) {
                scored.add(member);
            }
        }
        MarketMetrics.Survival survival = MarketMetrics.survivalPremium(scored);

        SurveillanceVO vo = new SurveillanceVO();
        vo.setTradeDate(date);
        vo.setCount(survival.getCount());
        vo.setAllCount(list.size());
        vo.setMatched(survival.getMatched());
        vo.setAvgPct(survival.getAvgPct());
        vo.setDropped(survival.getDropped());
        for (SurvivalMember member : list) {
            vo.getItems().add(toItem(member));
        }
        // 进分的排前面，其次还剩最久的：面板一眼要看到的是"哪几只把钱压在监管期里、还有几天"
        vo.getItems().sort(Comparator
                .comparing((SurveillanceVO.Item item) -> !item.isScored())
                .thenComparing(item -> -(item.getDays() - item.getDayIndex())));
        vo.setNote(survivalNote(survival, list.size() - scored.size()));
        return vo;
    }

    private static SurveillanceVO.Item toItem(SurvivalMember member) {
        SurveillanceVO.Item item = new SurveillanceVO.Item();
        item.setCode(member.getCode());
        item.setName(member.getName());
        item.setPct(member.getPct());
        item.setDayIndex(member.getDayIndex());
        item.setDays(member.getDays());
        SurvivalMember.ActiveEvent primary = member.getEvents().get(0);
        item.setKind(primary.getKind().name());
        item.setAnnDate(primary.getAnnDate());
        item.setScored(member.scored());
        item.setDescribe(member.describe());
        for (SurvivalMember.ActiveEvent event : member.getEvents()) {
            item.getEventLabels().add(event.getKind().label() + " "
                    + event.getAnnDate().toString().substring(5).replace('-', '/')
                    + " 第" + event.getDayIndex() + "/" + event.getDays() + "日");
        }
        return item;
    }

    /**
     * 算式串必须自己说清人群是哪个：换成"只有 SEVERE/EXCH 进分"之后，
     * 名单上 43 只而分母里 4 只是常态，写成"在列 43 家"就是在骗人。
     */
    private static String survivalNote(MarketMetrics.Survival survival, int displayOnly) {
        String tail = displayOnly > 0 ? "；另有 " + displayOnly + " 只例行异常波动，只展示不进分" : "";
        if (survival.getCount() == 0) {
            return "当日无严重异常波动/交易所监管在列（第 9 维不计入分母，不是 0 分）" + tail;
        }
        if (survival.getAvgPct() == null) {
            return "进分 " + survival.getCount() + " 家，但一只都没取到当日涨跌（第 9 维不计入分母）" + tail;
        }
        return "监管股今日溢价 = " + survival.getAvgPct() + "% = " + survival.getMatched()
                + " 只涨幅均值（进分 " + survival.getCount() + " 家：严重异常波动/交易所监管"
                + (survival.getDropped() > 0 ? "，另有 " + survival.getDropped() + " 只涨幅越出所在板块的日幅度上限被丢弃" : "")
                + "）" + tail;
    }

    // ---------- 纯推导（包级可见，离线单测） ----------

    /**
     * D0 之后、到 date（含 date）为止的交易日个数。
     *
     * 公告常常落在非交易日（周五收盘后、周末），所以 D0 本身不要求在序列里；
     * "第 k 日"数的是 D0 之后真实交易过的天数，公告当天不算（那天它还没来得及影响盘口）。
     * 实测对齐：哈药 08-21 严重异常波动，08-24 是第 1 日、09-04 是第 10 日、09-07 第 11 日出窗。
     */
    static int dayIndexAfter(LocalDate annDate, LocalDate date, List<LocalDate> tradingDays) {
        if (annDate == null || date == null || !date.isAfter(annDate)) {
            return 0;
        }
        int count = 0;
        for (LocalDate day : tradingDays) {
            if (day.isAfter(annDate) && !day.isAfter(date)) {
                count++;
            }
        }
        return count;
    }

    /** 一只票在同一天的全部生效事件；一起都没有在窗口内就返回 null（= 当天不在列）。 */
    static SurvivalMember memberOf(List<Surveillance> events, LocalDate date, List<LocalDate> tradingDays) {
        List<SurvivalMember.ActiveEvent> active = new ArrayList<>();
        Surveillance latest = null;
        for (Surveillance event : events) {
            SurveillanceKind kind = kindOf(event.getKind());
            if (kind == null || event.getAnnDate() == null) {
                continue;
            }
            if (latest == null || event.getAnnDate().isAfter(latest.getAnnDate())) {
                latest = event;
            }
            int index = dayIndexAfter(event.getAnnDate(), date, tradingDays);
            if (index >= 1 && index <= kind.days()) {
                SurvivalMember.ActiveEvent window =
                        SurvivalMember.ActiveEvent.of(kind, event.getAnnDate(), index, kind.days());
                // 同一天可能有两份公告（公司自己的异常波动 + 控股股东那份回复），一个窗口只算一次
                if (!active.contains(window)) {
                    active.add(window);
                }
            }
        }
        if (active.isEmpty()) {
            return null;
        }
        // 进分的那一起排前面，其次剩余天数多的：它才是"为什么今天还在列、还在分母里"的那一起
        active.sort(Comparator
                .comparing((SurvivalMember.ActiveEvent event) -> !event.getKind().scores())
                .thenComparing(event -> -event.remaining()));

        SurvivalMember member = new SurvivalMember();
        member.setCode(latest.getStockCode());
        member.setName(latest.getStockName());
        member.setEvents(active);
        member.setDayIndex(active.get(0).getDayIndex());
        member.setDays(active.get(0).getDays());
        return member;
    }

    private static SurveillanceKind kindOf(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return SurveillanceKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("库里出现未知监管类别 {}，这一条不计入窗口", raw);
            return null;
        }
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static long countSucceeded(List<RefreshOutcome> outcomes) {
        return outcomes.stream().filter(RefreshOutcome::isOk).count();
    }

    private static long countFailed(List<RefreshOutcome> outcomes) {
        return outcomes.size() - countSucceeded(outcomes);
    }

    private static int sumWritten(List<RefreshOutcome> outcomes) {
        int sum = 0;
        for (RefreshOutcome outcome : outcomes) {
            sum += outcome.getWritten();
        }
        return sum;
    }

    /** 一只票一次刷新的结果。failed 与"没有异动公告"必须可区分，否则一次抖动就把监管期永久漏掉。 */
    public static final class RefreshOutcome {

        private final String code;
        private final boolean ok;
        private final String reason;
        private final int announcements;
        private final int matchedNotices;
        private final int written;
        private final int unmatchedSignals;

        private RefreshOutcome(String code, boolean ok, String reason, int announcements,
                               int matchedNotices, int written, int unmatchedSignals) {
            this.code = code;
            this.ok = ok;
            this.reason = reason;
            this.announcements = announcements;
            this.matchedNotices = matchedNotices;
            this.written = written;
            this.unmatchedSignals = unmatchedSignals;
        }

        static RefreshOutcome ok(String code, int announcements, int matchedNotices, int written,
                                 int unmatchedSignals) {
            return new RefreshOutcome(code, true, null, announcements, matchedNotices, written, unmatchedSignals);
        }

        static RefreshOutcome failed(String code, String reason) {
            return new RefreshOutcome(code, false, reason, 0, 0, 0, 0);
        }

        public String getCode() {
            return code;
        }

        public boolean isOk() {
            return ok;
        }

        public String getReason() {
            return reason;
        }

        /** 这个窗口里上游一共给了多少条公告（含不属于异动的）。 */
        public int getAnnouncements() {
            return announcements;
        }

        public int getMatchedNotices() {
            return matchedNotices;
        }

        public int getWritten() {
            return written;
        }

        public int getUnmatchedSignals() {
            return unmatchedSignals;
        }
    }
}
