package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.dto.MarketFields;
import com.emotion.entity.IndexClose;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.EastmoneyClient;
import com.emotion.market.MarketDataException;
import com.emotion.market.MarketMetrics;
import com.emotion.market.PoolResult;
import com.emotion.market.PoolRow;
import com.emotion.market.PremiumGroup;
import com.emotion.market.TencentClient;
import com.emotion.market.TencentClient.DayBar;
import com.emotion.market.TencentClient.StockQuote;
import com.emotion.util.ReviewImportParser;
import com.emotion.util.TemperatureCalculator;
import com.emotion.vo.MarketSnapshotVO;
import com.emotion.vo.MarketStocksVO;
import com.emotion.vo.PremiumPoolVO;
import com.emotion.vo.PremiumTiersVO;

/**
 * 复盘七项里六个可计算字段的取数编排。
 *
 * 三条贯穿全局的原则：
 * 1. 拿不到的字段整个 key 不出现在 filled 里，绝不写 0——0 是一个真实读数，会被打分当成
 *    "当天真的没有跌停"拿去出分；NULL 才是"该维未评"，整维从分母剔除。
 * 2. 上游并行、整体有预算，到点收多少算多少，任何一个源挂掉都只是少一个字段。
 * 3. 每个数字都往 notes 里留一条算式，让人能核对而不是被迫相信。
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** 所有"今天"都必须以东八区为准，JVM 默认时区在别的机器上会算错交易日。 */
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final int CLOSE_MINUTE_OF_DAY = 15 * 60 + 5;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 合理性守卫：越界宁可留给人工，也不写一个看着像真的错值。 */
    private static final BigDecimal PREMIUM_MIN = BigDecimal.valueOf(-20);
    private static final BigDecimal PREMIUM_MAX = BigDecimal.valueOf(20);
    private static final BigDecimal AMOUNT_MIN_YI = BigDecimal.valueOf(3000);
    private static final BigDecimal AMOUNT_MAX_YI = BigDecimal.valueOf(60000);

    private static final List<String> MANUAL_FIELDS = Collections.unmodifiableList(
            java.util.Arrays.asList("mainTheme", "leadingStock", "leadingStockStatus", "scoreTheme"));
    private static final List<String> ALL_MARKET_FIELDS = Collections.unmodifiableList(
            java.util.Arrays.asList("maxConsecutiveLimit", "limitUpCount", "limitDownCount",
                    "yesterdayLimitPremium", "brokenBoardRate", "bigLossCount", "totalVolume"));

    private final EastmoneyClient eastmoney;
    private final TencentClient tencent;
    private final StockPoolWriter stockPoolWriter;
    private final PremiumTierStore premiumTierStore;
    private final IndexCloseStore indexCloseStore;
    private final MarketStockMapper marketStockMapper;
    private final ExecutorService executor;
    private final long budgetMs;
    private final long intradayTtlMs;
    private final int maxCacheEntries;

    private final Map<LocalDate, Snapshot> cache = new ConcurrentHashMap<>();
    private final Map<LocalDate, Object> locks = new ConcurrentHashMap<>();

    public MarketDataService(EastmoneyClient eastmoney,
                             TencentClient tencent,
                             StockPoolWriter stockPoolWriter,
                             PremiumTierStore premiumTierStore,
                             IndexCloseStore indexCloseStore,
                             MarketStockMapper marketStockMapper,
                             @Qualifier("marketExecutor") ExecutorService executor,
                             @Value("${market.budget-ms:12000}") long budgetMs,
                             @Value("${market.intraday-ttl-ms:60000}") long intradayTtlMs,
                             @Value("${market.cache-max-entries:256}") int maxCacheEntries) {
        this.eastmoney = eastmoney;
        this.tencent = tencent;
        this.stockPoolWriter = stockPoolWriter;
        this.premiumTierStore = premiumTierStore;
        this.indexCloseStore = indexCloseStore;
        this.marketStockMapper = marketStockMapper;
        this.executor = executor;
        this.budgetMs = budgetMs;
        this.intradayTtlMs = intradayTtlMs;
        this.maxCacheEntries = Math.max(1, maxCacheEntries);
    }

    public MarketSnapshotVO snapshot(LocalDate requestedDate, boolean refresh) {
        LocalDate date = requestedDate != null ? requestedDate : LocalDate.now(CN);
        if (isWeekend(date)) {
            throw new MarketDataException(date + " 是周末，非交易日");
        }
        if (date.isAfter(LocalDate.now(CN))) {
            throw new MarketDataException("不能拉取未来日期：" + date);
        }
        if (refresh) {
            cache.remove(date);
        }

        // 每个日期一把锁：连点按钮时只让第一个线程去打上游，其余等结果读缓存
        synchronized (locks.computeIfAbsent(date, key -> new Object())) {
            Snapshot cached = cache.get(date);
            if (cached != null && !isStale(cached, date)) {
                return toVO(cached, date, true);
            }
            Snapshot fresh = compute(date);
            store(date, fresh);
            return toVO(fresh, date, false);
        }
    }

    // ---------- 明细回读 ----------

    /**
     * 读一日盘面明细，组出仪表盘 hover 要用的四个视图。
     *
     * 梯队、断档、首板家数在这里算而不在前端算：它们都由同一个"涨停池 lbc 分布"派生，
     * 分两处算就会出现两处口径不一致，而这三格是用户判断"还剩几只接力标的"的全部依据。
     */
    public MarketStocksVO stocks(LocalDate requestedDate) {
        LocalDate date = requestedDate != null ? requestedDate : LocalDate.now(CN);
        MarketStocksVO vo = new MarketStocksVO();
        vo.setTradeDate(date);
        List<MarketStock> rows = marketStockMapper.selectList(
                new LambdaQueryWrapper<MarketStock>().eq(MarketStock::getTradeDate, date));
        vo.setAvailable(!rows.isEmpty());
        if (rows.isEmpty()) {
            return vo;
        }

        Map<Integer, MarketStocksVO.Tier> tiers = new TreeMap<>(Collections.reverseOrder());
        int firstBoard = 0;
        int limitUp = 0;
        for (MarketStock row : rows) {
            if (!MarketStock.POOL_LIMIT_UP.equals(row.getPool())) {
                continue;
            }
            limitUp++;
            int board = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (board <= 1) {
                firstBoard++;
                continue;
            }
            tiers.computeIfAbsent(board, key -> {
                MarketStocksVO.Tier tier = new MarketStocksVO.Tier();
                tier.setBoard(key);
                return tier;
            }).getStocks().add(item(row));
        }
        vo.setFirstBoardCount(firstBoard);
        vo.setLadder(new ArrayList<>(tiers.values()));
        if (!tiers.isEmpty()) {
            for (int board = 2; board < tiers.keySet().iterator().next(); board++) {
                if (!tiers.containsKey(board)) {
                    vo.getGapBoards().add(board);
                }
            }
        }

        for (MarketStock row : rows) {
            if (MarketStock.POOL_LIMIT_DOWN.equals(row.getPool())) {
                vo.getLimitDown().add(item(row));
            } else if (row.getBigLoss() != null && row.getBigLoss() == 1) {
                vo.getBigLoss().add(item(row));
            }
        }
        vo.setLimitUpCount(limitUp);
        vo.setLimitDownCount(vo.getLimitDown().size());
        // 大面按回撤从大到小排：排在最前面的那几只才是"今天最伤人的票"
        vo.getBigLoss().sort((a, b) -> deepestFirst(a.getPullback(), b.getPullback()));
        vo.getLimitDown().sort((a, b) -> worstFirst(a.getPct(), b.getPct()));
        return vo;
    }

    /** null 一律沉底：缺字段不能冒充"最深的大面"或"最惨的跌停"。 */
    private static int deepestFirst(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : 1) : -1;
        }
        return right.compareTo(left);
    }

    private static int worstFirst(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : 1) : -1;
        }
        return left.compareTo(right);
    }

    private static MarketStocksVO.Item item(MarketStock row) {
        MarketStocksVO.Item item = new MarketStocksVO.Item();
        item.setCode(row.getCode());
        item.setName(row.getName());
        item.setIndustry(row.getIndustry());
        item.setPct(row.getChangePct());
        item.setPullback(row.getPullbackPct());
        return item;
    }

    // ---------- 档位溢价 ----------

    /** 读一日已入库的档位溢价。没有这一天时 available=false，前端要写成"未评"而不是 0%。 */
    public PremiumTiersVO premiumTiers(LocalDate requestedDate) {
        LocalDate date = requestedDate != null ? requestedDate : LocalDate.now(CN);
        return tiersVO(date, premiumTierStore.read(date), prevPoolDate(date));
    }

    /**
     * 回补取数清单：这一天该给哪些票拉日 K，全部由服务端定。
     * 系统里没有交易日历，"上一交易日"是从明细表回看出来的；让脚本再推一遍就会有两份答案。
     */
    public PremiumPoolVO premiumPool(LocalDate requestedDate) {
        LocalDate date = requestedDate != null ? requestedDate : LocalDate.now(CN);
        PremiumPoolVO vo = new PremiumPoolVO();
        vo.setTradeDate(date);
        LocalDate prevDate = prevPoolDate(date);
        vo.setPrevTradeDate(prevDate);
        if (prevDate == null) {
            return vo;
        }
        for (PoolRow row : poolRows(prevDate)) {
            Integer lbc = row.getLbc();
            if (lbc == null || lbc <= 1) {
                continue;   // 首板不进档位溢价，就不必为它多拉一次数据
            }
            PremiumPoolVO.Item item = new PremiumPoolVO.Item();
            item.setCode(row.getCode());
            item.setName(row.getName());
            item.setSymbol(TencentClient.toGtimgCode(row));
            item.setBoard(lbc);
            vo.getItems().add(item);
        }
        return vo;
    }

    /** 一段日 K。上游那套"忽略 start、只认截至 end 的最近 N 根"的怪脾气只在这里处理一次。 */
    public List<TencentClient.DayBar> dailyBars(String symbol, LocalDate start, LocalDate end) {
        if (symbol == null || symbol.trim().isEmpty() || start == null || end == null) {
            throw new MarketDataException("日 K 需要 symbol、start、end 三个参数");
        }
        return tencent.dailyBars(symbol.trim(), start, end);
    }

    /**
     * 历史日回补：脚本只负责取日 K（一次请求覆盖一只票的全窗口），档位归属、脏值守卫、
     * 加权合成一律在这里做——打分逻辑存两份就一定会对不上。
     *
     * 昨日池读的是 {@code t_market_stock} 而不是东财：上游只留最近约 15 个交易日，
     * 明细表一旦落库就永久可重算，回补不会因为窗口滑掉而变成一次性动作。
     */
    public PremiumTiersVO savePremiumTiers(LocalDate date, Map<String, BigDecimal> pctByCode) {
        if (date == null) {
            throw new MarketDataException("必须指定 tradeDate");
        }
        LocalDate prevDate = prevPoolDate(date);
        if (prevDate == null) {
            throw new MarketDataException(date + " 之前没有涨停池明细，无法算档位溢价"
                    + "（先拉一次 " + date + " 的行情把明细落进库）");
        }
        MarketMetrics.PremiumTiers tiers = MarketMetrics.premiumTiers(poolRows(prevDate), pctByCode);
        int written = premiumTierStore.replaceForDate(date, tiers);
        if (written < 0) {
            throw new MarketDataException(prevDate + " 的涨停池里没有一只非首板，" + date + " 无档位可写");
        }
        return tiersVO(date, tiers, prevDate);
    }

    /**
     * 上一交易日 = 库里最后一个早于 date 且有涨停明细的日期。
     * 系统里没有交易日历表，明细表本身就是"哪天真的交易日过"的记录。
     */
    private LocalDate prevPoolDate(LocalDate date) {
        List<MarketStock> rows = marketStockMapper.selectList(
                new LambdaQueryWrapper<MarketStock>()
                        .select(MarketStock::getTradeDate)
                        .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP)
                        .lt(MarketStock::getTradeDate, date)
                        .orderByDesc(MarketStock::getTradeDate)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0).getTradeDate();
    }

    /** 把某日的涨停池明细还原成打档位要用的行：只需要 code / market / 连板数三件事。 */
    private List<PoolRow> poolRows(LocalDate date) {
        List<PoolRow> rows = new ArrayList<>();
        for (MarketStock stock : marketStockMapper.selectList(
                new LambdaQueryWrapper<MarketStock>()
                        .eq(MarketStock::getTradeDate, date)
                        .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP))) {
            PoolRow row = new PoolRow();
            row.setCode(stock.getCode());
            row.setName(stock.getName());
            row.setMarket(stock.getMarket());
            row.setLbc(stock.getConsecutive());
            rows.add(row);
        }
        return rows;
    }

    /** 三组的展示顺序：高位在最前——使用者要的读法是"顶端还有没有人赚钱"，不是从低位数上去。 */
    private static final PremiumGroup[] GROUPS_HIGH_FIRST = {
            PremiumGroup.HIGH, PremiumGroup.MID, PremiumGroup.LOW
    };

    private static PremiumTiersVO tiersVO(LocalDate date, MarketMetrics.PremiumTiers tiers,
                                          LocalDate prevDate) {
        PremiumTiersVO vo = new PremiumTiersVO();
        vo.setTradeDate(date);
        vo.setPrevTradeDate(prevDate);
        vo.setAvailable(tiers != null && !tiers.getTiers().isEmpty());
        vo.setGroups(new ArrayList<>());
        vo.setTiers(new ArrayList<>());
        if (tiers == null) {
            vo.setStructure(TemperatureCalculator.premiumStructure(null));
            return vo;
        }
        int h = tiers.maxBoard();
        vo.setTopBoard(h);
        vo.setMidLine(PremiumGroup.midLine(h));
        vo.setBinNote(binNote(h, tiers));
        vo.setFirstBoard(tiers.getFirstBoard());
        vo.setConsidered(tiers.getConsidered());
        vo.setMatched(tiers.getMatched());
        vo.setWeightedPct(TemperatureCalculator.compositePremiumPct(tiers));
        vo.setStructure(TemperatureCalculator.premiumStructure(tiers));
        // getTiers() 是升序的内部列表，倒过来展示要在副本上做：原地反转会连带改掉别处的读法
        List<MarketMetrics.TierPremium> desc = new ArrayList<>(tiers.getTiers());
        desc.sort(Comparator.comparingInt(MarketMetrics.TierPremium::getBoard).reversed());
        for (MarketMetrics.TierPremium tier : desc) {
            PremiumTiersVO.Tier item = new PremiumTiersVO.Tier();
            item.setBoard(tier.getBoard());
            item.setLabel(tier.getBoard() >= MarketMetrics.MAX_BOARD ? "8+" : String.valueOf(tier.getBoard()));
            item.setGroup(tiers.groupOf(tier).name());
            item.setStockCount(tier.getStockCount());
            item.setMatched(tier.getMatched());
            item.setAvgPct(tier.getAvgPct());
            item.setMaxPct(tier.getMaxPct());
            item.setMinPct(tier.getMinPct());
            vo.getTiers().add(item);
        }
        for (PremiumGroup group : GROUPS_HIGH_FIRST) {
            MarketMetrics.GroupPremium aggregate = tiers.group(group);
            PremiumTiersVO.Group item = new PremiumTiersVO.Group();
            item.setGroup(group.name());
            item.setLabel(group.label(h));
            item.setStockCount(aggregate.getStockCount());
            item.setMatched(aggregate.getMatched());
            item.setAvgPct(aggregate.getAvgPct());
            item.setScore(TemperatureCalculator.scoreGroup(tiers, group));
            item.setWeight(BigDecimal.valueOf(TemperatureCalculator.weight(group)));
            vo.getGroups().add(item);
        }
        return vo;
    }

    /** 中位线是活的，所以每次都要把"今天这条线是怎么画出来的"随数交出去。 */
    private static String binNote(int h, MarketMetrics.PremiumTiers tiers) {
        if (h <= 0) {
            return "当天没有非首板档位，无从定中位线";
        }
        int m = PremiumGroup.midLine(h);
        StringBuilder text = new StringBuilder("中位线按前一天最高板 " + h + " 板的一半定：M=" + m
                + "，" + PremiumGroup.LOW.label(h) + " / " + PremiumGroup.MID.label(h)
                + " / " + PremiumGroup.HIGH.label(h));
        if (tiers.maxBoardCapped()) {
            text.append("；8 板及以上已并成一档，真实最高板可能更高，半线按 8 取会偏低一档");
        }
        return text.toString();
    }

    /**
     * 档位溢价落库是这次拉取的副作用，和明细落库同一待遇：写失败只让这一天少一个维度，
     * 六个已经算出来的字段照样要交回去。
     */
    private void writePremiumTiers(Snapshot snap) {
        try {
            int written = premiumTierStore.replaceForDate(snap.requestedDate, snap.premiumTiers);
            if (written < 0) {
                snap.notes.add("档位溢价未入库（昨日池里无非首板，或本次没走到报价那一步），保留上一次数据");
            } else {
                snap.notes.add("档位溢价已入库 " + written + " 档");
            }
        } catch (Exception e) {
            log.warn("档位溢价入库失败: {}", e.toString());
            snap.warnings.add("档位溢价入库失败：这一天不会参与打分的档位维");
        }
    }

    // ---------- 取数 ----------

    private Snapshot compute(LocalDate date) {
        final long deadline = System.currentTimeMillis() + budgetMs;
        Snapshot snap = new Snapshot();
        snap.requestedDate = date;
        snap.live = date.isEqual(LocalDate.now(CN)) && beforeClose();

        // 阶段一：四个上游互不依赖，并行。腾讯快照日兼任"请求的日期有没有数据"的判据。
        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> eastmoney.limitUp(date));
        tasks.add(() -> eastmoney.limitDown(date));
        tasks.add(() -> eastmoney.broken(date));
        tasks.add(() -> tencent.indexQuotes());
        tasks.add(() -> findPrevLimitUpPool(date));
        // 五大指数收盘只在缺的时候才取：一只一次日 K 请求，齐了就不该每天再花这五次。
        // 排最后，所以跳过时 at(futures, 5) 自然拿到 null，不用另写分支。
        boolean needIndexCloses = indexClosesIncomplete(date);
        if (needIndexCloses) {
            tasks.add(() -> tencent.indexDayBars(date));
        }
        List<Future<Object>> futures = run(tasks, deadline);

        PoolResult limitUp = at(futures, 0);
        PoolResult limitDown = at(futures, 1);
        PoolResult broken = at(futures, 2);
        Map<String, StockQuote> indexes = at(futures, 3);
        PrevPool prevPool = at(futures, 4);
        Map<String, DayBar> indexBars = needIndexCloses ? at(futures, 5) : null;

        snap.snapshotDate = indexes == null ? null
                : TencentClient.snapshotDate(indexes, tencent.shIndexCode());
        snap.prevTradeDate = prevPool == null ? null : prevPool.date;

        // 池子接口对晚于最新交易日的日期不报错，而是回落到最近一天的数据（周六回了周五的 39 家）。
        // 只有腾讯给的快照日能识破这件事，所以这一步必须排在填字段之前。
        if (snap.snapshotDate == null) {
            snap.warnings.add("腾讯行情快照日未知，无法确认 " + date + " 是否交易日，请自行核对");
        } else if (date.isAfter(snap.snapshotDate)) {
            throw new MarketDataException("行情源最新数据只到 " + snap.snapshotDate + "，"
                    + date + " 还没有数据（可能不是交易日）");
        }
        if (allPoolsEmpty(limitUp, limitDown, broken)) {
            // 三个池同时为空在真实盘面几乎不可能，这个日期多半是早于可回溯范围的历史日或节假日。
            // 此时各字段都会被填成 0——一个看起来像真的错值，所以直接拒绝。
            throw new MarketDataException(date + " 的涨停/跌停/炸板三个池全为空，该日期可能不是交易日，"
                    + "或已超出行情源可回溯范围（三个池子只保留最近约 15 个交易日，"
                    + "2026-09-05 实测 08-17 可取、08-14 已为空），请手工填写");
        }

        MarketFields fields = new MarketFields();
        fillHeightAndCounts(snap, fields, limitUp, limitDown);
        fillBrokenRate(snap, fields, limitUp, broken);
        MarketMetrics.BigLoss bigLoss = fillBigLoss(snap, fields, broken);
        fillAmount(snap, fields, indexes);
        fillPremium(snap, fields, deadline, prevPool);

        if (missingCount(fields) == ALL_MARKET_FIELDS.size()) {
            throw new MarketDataException("行情源未返回任何可用数据，请稍后重试或手工填写");
        }
        snap.fields = fields;
        writeDetails(snap, limitUp, limitDown, broken, bigLoss);
        writePremiumTiers(snap);
        writeIndexCloses(snap, indexBars);
        return snap;
    }

    /**
     * 明细落库是这次拉取的副作用，不是它的前提：写失败只让 hover 少一块内容，
     * 六个已经算出来的字段照样要交回去。
     */
    private void writeDetails(Snapshot snap, PoolResult limitUp, PoolResult limitDown,
                              PoolResult broken, MarketMetrics.BigLoss bigLoss) {
        try {
            int written = stockPoolWriter.replaceForDate(snap.requestedDate, limitUp, limitDown, broken,
                    bigLoss == null ? Collections.<PoolRow>emptyList() : bigLoss.getRows());
            if (written < 0) {
                snap.warnings.add("盘面个股明细未更新：三个池没有全部取到，保留了上一次的明细");
            } else {
                snap.notes.add("盘面个股明细已入库 " + written + " 行（连板梯队/大面/跌停名单可在仪表盘 hover 查看）");
            }
        } catch (Exception e) {
            log.warn("盘面明细写入失败: {}", e.toString());
            snap.warnings.add("盘面个股明细入库失败，卡面上不会有 hover 名单");
        }
    }

    /**
     * 五大指数收盘。和 {@code t_market_stock}、{@code t_premium_tier} 同一族：公开数据、不带人工判断，
     * 所以允许 /snapshot 顺带写。只覆盖真取到的那几只（{@link IndexCloseStore#upsertForDate}），
     * 缺一只不该把 md 导进来的另外几只一起抹掉。
     */
    private void writeIndexCloses(Snapshot snap, Map<String, DayBar> bars) {
        if (bars == null) {
            return;
        }
        try {
            List<IndexClose> rows = indexRows(snap.requestedDate, bars);
            int written = indexCloseStore.upsertForDate(snap.requestedDate, rows);
            if (written == 0) {
                snap.warnings.add("指数收盘这次一只都没取到，【一】那五行仍要从复盘 md 带");
                return;
            }
            snap.notes.add("指数收盘已入库 " + written + " 行（腾讯日 K，涨幅由前一根收盘算出）");
            if (written < tencent.expectedIndexCount()) {
                snap.warnings.add("指数收盘只取到 " + written + "/" + tencent.expectedIndexCount()
                        + "，缺的那几只仍显示为空——不是那天没交易，是日 K 没给");
            }
        } catch (Exception e) {
            log.warn("指数收盘写入失败: {}", e.toString());
            snap.warnings.add("指数收盘入库失败，【一】的指数行仍要从复盘 md 带");
        }
    }

    /**
     * 这天有几只指数<b>带着收盘价</b>。要不要再取数、和面板上那个 n/5，都读这一个方法，
     * 两处必然是同一个数。
     *
     * <p>判据是收盘价而不是行数：md 导入的 9/3 有五行，可只有上证那行填了收盘价，
     * 按行数算已经"齐了"，而补齐另外四个收盘价正是自动取数要解决的那件事。
     */
    private int closedIndexCount(LocalDate date) {
        int withClose = 0;
        for (IndexClose row : indexCloseStore.read(date)) {
            if (row.getClosePrice() != null) {
                withClose++;
            }
        }
        return withClose;
    }

    private boolean indexClosesIncomplete(LocalDate date) {
        return closedIndexCount(date) < tencent.expectedIndexCount();
    }

    /** 包级可见 + 纯函数：单测直接喂构造的 DayBar，不联网、不碰库。 */
    static List<IndexClose> indexRows(LocalDate date, Map<String, DayBar> barsByCode) {
        List<IndexClose> rows = new ArrayList<>();
        if (date == null || barsByCode == null) {
            return rows;
        }
        for (Map.Entry<String, String> entry : ReviewImportParser.KNOWN_INDEXES.entrySet()) {
            DayBar bar = barsByCode.get(entry.getKey());
            if (bar == null) {
                continue;
            }
            IndexClose row = new IndexClose();
            row.setTradeDate(date);
            row.setIndexCode(entry.getKey());
            row.setIndexName(entry.getValue());
            row.setClosePrice(bar.getClose());
            row.setChangePct(bar.getPct());
            rows.add(row);
        }
        return rows;
    }

    private void fillHeightAndCounts(Snapshot snap, MarketFields fields, PoolResult limitUp, PoolResult limitDown) {
        if (limitUp != null && limitUp.isOk()) {
            int height = limitUp.maxConsecutive();
            fields.setMaxConsecutiveLimit(height);
            fields.setLimitUpCount(limitUp.getTc());
            snap.notes.add("涨停 " + limitUp.getTc() + " 家，连板高度 " + height + " 板（明细 "
                    + limitUp.getRows().size() + "/" + limitUp.getTc() + " 只）");
            warnIfTruncated(snap, "涨停池", limitUp);
        } else {
            snap.warnings.add("涨停池未取得：" + reason(limitUp));
        }

        if (limitDown != null && limitDown.isOk()) {
            fields.setLimitDownCount(limitDown.getTc());
            snap.notes.add("跌停 " + limitDown.getTc() + " 家");
            warnIfTruncated(snap, "跌停池", limitDown);
        } else {
            snap.warnings.add("跌停池未取得：" + reason(limitDown));
        }
    }

    /**
     * 炸板率按 03 篇原文取"次数"口径，算式原样写进 notes——
     * 这一维对口径最敏感，使用者必须能看到数字是怎么来的。
     */
    private void fillBrokenRate(Snapshot snap, MarketFields fields, PoolResult limitUp, PoolResult broken) {
        if (limitUp == null || !limitUp.isOk() || broken == null || !broken.isOk()) {
            snap.warnings.add("炸板率未取得（需要涨停池与炸板池同时可用）");
            return;
        }
        int breaks = MarketMetrics.breakCount(broken.getRows());
        int sealed = limitUp.getTc();
        warnIfTruncated(snap, "炸板池", broken);

        BigDecimal rate = MarketMetrics.brokenRate(breaks, sealed);
        if (rate == null) {
            fields.setBrokenBoardRate(BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP));
            snap.notes.add("当日无涨停也无炸板，炸板率按 0.0% 记");
            return;
        }
        if (rate.signum() < 0 || rate.compareTo(HUNDRED) > 0) {
            snap.warnings.add("炸板率算出 " + rate + "%，超出 0~100 区间，已留给人工");
            return;
        }
        fields.setBrokenBoardRate(rate);
        snap.notes.add("炸板率 = 炸板 " + breaks + " 次 ÷（炸板 " + breaks + " 次 + 封住 " + sealed
                + " 家）= " + rate + "%");
        int resealed = MarketMetrics.breakCount(limitUp.getRows());
        if (resealed > 0) {
            snap.notes.add("口径说明：涨停池内另有 " + resealed
                    + " 次「封住前曾打开」未计入分子分母；若按家数口径则为 "
                    + MarketMetrics.percent(broken.getTc(), broken.getTc() + sealed) + "%");
        }
    }

    private MarketMetrics.BigLoss fillBigLoss(Snapshot snap, MarketFields fields, PoolResult broken) {
        if (broken == null || !broken.isOk()) {
            snap.warnings.add("大面数未取得：炸板池不可用");
            return null;
        }
        MarketMetrics.BigLoss bigLoss = MarketMetrics.bigLoss(broken.getRows());
        fields.setBigLossCount(bigLoss.getCount());
        snap.notes.add("大面 " + bigLoss.getCount() + " 家（自涨停回撤 >7% 且收盘绿盘，样本 "
                + broken.getRows().size() + " 只）");
        if (bigLoss.getUnusable() > 0) {
            snap.warnings.add(bigLoss.getUnusable() + " 只炸板股缺涨停价或涨跌幅，未纳入大面统计");
        }
        return bigLoss;
    }

    /**
     * 成交额两条路：快照日当天用实时快照（盘中也有数），历史日期用腾讯日 K 回溯。
     * 东财的 push2his / push2 / 92.push2his 在本机实测全是空响应，不可依赖。
     */
    private void fillAmount(Snapshot snap, MarketFields fields, Map<String, StockQuote> indexes) {
        boolean isSnapshotDay = snap.snapshotDate != null && snap.snapshotDate.equals(snap.requestedDate);
        BigDecimal amount;
        String source;
        if (isSnapshotDay) {
            // 当天走实时快照：盘中就能拿到累计成交额，日 K 这时还没有这根柱子
            amount = indexes == null ? null
                    : TencentClient.twoMarketAmountBillion(indexes, tencent.shIndexCode(), tencent.szIndexCode());
            source = "腾讯沪+深指数实时成交额";
        } else {
            amount = tencent.twoMarketDayAmountBillion(snap.requestedDate);
            source = "腾讯日 K 回溯";
        }
        if (amount == null) {
            snap.notes.add("两市成交额未取得（" + source + "），" + snap.requestedDate + " 需手工填写");
            return;
        }
        if (amount.compareTo(AMOUNT_MIN_YI) < 0 || amount.compareTo(AMOUNT_MAX_YI) > 0) {
            snap.warnings.add("两市成交额 " + amount + " 亿超出合理区间 " + AMOUNT_MIN_YI + "~"
                    + AMOUNT_MAX_YI + " 亿，已留给人工");
            return;
        }
        fields.setTotalVolume(amount);
        snap.notes.add("两市成交额 " + amount + " 亿（" + source + "，实测深市给的是全市场口径）");
    }

    private void fillPremium(Snapshot snap, MarketFields fields, long deadline, PrevPool prevPool) {
        if (prevPool == null || prevPool.result.getRows().isEmpty()) {
            snap.warnings.add("昨日涨停池未取得，溢价无法计算");
            return;
        }
        if (snap.snapshotDate == null) {
            snap.warnings.add("腾讯行情快照日未知，溢价无法计算");
            return;
        }
        if (!snap.snapshotDate.equals(snap.requestedDate)) {
            // 腾讯只有最新快照，历史日期要逐只回溯得几十次请求，会撞上东财限流，只能人工填。
            snap.notes.add("「昨日涨停溢价」只能按当前快照日 " + snap.snapshotDate + " 取数，"
                    + snap.requestedDate + " 需手工填写");
            return;
        }
        List<String> codes = TencentClient.toGtimgCodes(prevPool.result.getRows());
        List<Callable<Object>> tasks = Collections.<Callable<Object>>singletonList(() -> tencent.quotes(codes));
        List<Future<Object>> futures = run(tasks, deadline);
        Map<String, StockQuote> quotes = at(futures, 0);
        if (quotes == null || quotes.isEmpty()) {
            snap.warnings.add("溢价未取得：批量报价请求失败");
            return;
        }
        // 档位溢价吃的是同一次批量报价，零新增请求。它比整池均值晚一步被丢弃保护：
        // 整池越界只说明那一个标量不能用，逐档仍可能各自有效。
        snap.premiumTiers = MarketMetrics.premiumTiers(prevPool.result.getRows(),
                pctByCode(prevPool.result.getRows(), quotes));
        for (String warning : snap.premiumTiers.getWarnings()) {
            snap.warnings.add("档位溢价：" + warning);
        }
        MarketMetrics.Premium premium = MarketMetrics.premium(prevPool.result.getRows(), quotes);
        if (premium.getMatched() == 0) {
            snap.warnings.add("溢价未取得：昨日涨停股在今日行情里一只都没匹配上");
            return;
        }
        BigDecimal value = premium.getValue();
        if (value.compareTo(PREMIUM_MIN) < 0 || value.compareTo(PREMIUM_MAX) > 0) {
            snap.warnings.add("昨日涨停溢价算出 " + value + "%，超出 ±20% 区间，已留给人工");
            return;
        }
        fields.setYesterdayLimitPremium(value);
        snap.notes.add("昨日（" + prevPool.date + "）涨停溢价 = " + premium.getMatched()
                + " 只今日涨幅均值 = " + value + "%（昨日涨停 " + prevPool.result.getTc()
                + " 家，报价匹配 " + premium.getMatched() + "/" + codes.size() + " 只）");
        if (premium.getMatched() < prevPool.result.getTc()) {
            snap.warnings.add("溢价样本 " + premium.getMatched() + " 只少于昨日涨停 "
                    + prevPool.result.getTc() + " 家，均值只代表取到报价的部分");
        }
        MarketMetrics.PremiumTiers tiers = snap.premiumTiers;
        snap.notes.add("档位溢价（进分口径）" + tiersDescription(tiers)
                + "；含首板整池 " + value + "% 只做展示");
    }

    /** 池子里的行 → code:今日涨跌幅%。档位溢价与历史回补共用这一个形状。 */
    private static Map<String, BigDecimal> pctByCode(List<PoolRow> rows, Map<String, StockQuote> quotes) {
        Map<String, BigDecimal> pct = new HashMap<>();
        for (PoolRow row : rows) {
            StockQuote quote = quotes.get(TencentClient.toGtimgCode(row));
            if (quote != null && quote.getChangePct() != null && row.getCode() != null) {
                pct.put(row.getCode(), quote.getChangePct());
            }
        }
        return pct;
    }

    private static String tiersDescription(MarketMetrics.PremiumTiers tiers) {
        if (tiers == null || tiers.getTiers().isEmpty()) {
            return "无（非首板样本为零）";
        }
        StringBuilder sb = new StringBuilder();
        for (MarketMetrics.TierPremium tier : tiers.getTiers()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(tier.getBoard()).append("板 ").append(tier.getMatched()).append('/').append(tier.getStockCount());
            sb.append(tier.getAvgPct() == null ? " 无价" : " " + tier.getAvgPct() + "%");
        }
        return sb.toString() + "（首板 " + tiers.getFirstBoard() + " 家不计入）";
    }

    /**
     * 回溯上一交易日：往前找第一个涨停池非空的日子。判据只能用"池子为空"，
     * 因为接口对节假日和超出可回溯范围的日期都是 rc=0、tc=0，不会报错。
     * 真实交易日不存在一只涨停都没有的情况，所以这个判据够用。
     * 请求本身失败时立刻停手——再往前一天算出来的就是"前两个交易日涨停股的今日表现"。
     */
    private PrevPool findPrevLimitUpPool(LocalDate date) {
        LocalDate cursor = date.minusDays(1);
        LocalDate floor = date.minusDays(12);
        while (!cursor.isBefore(floor)) {
            if (!isWeekend(cursor)) {
                PoolResult result = eastmoney.limitUp(cursor);
                if (!result.isOk()) {
                    return null;
                }
                if (result.getTc() > 0) {
                    return new PrevPool(cursor, result);
                }
            }
            cursor = cursor.minusDays(1);
        }
        return null;
    }

    private List<Future<Object>> run(List<Callable<Object>> tasks, long deadline) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            return Collections.emptyList();
        }
        try {
            return executor.invokeAll(tasks, remaining, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T at(List<Future<Object>> futures, int index) {
        if (index >= futures.size()) {
            return null;
        }
        Future<Object> future = futures.get(index);
        if (future.isCancelled()) {
            return null;
        }
        try {
            return (T) future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.warn("行情子任务异常: {}", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
            return null;
        }
    }

    // ---------- 缓存 ----------

    private boolean isStale(Snapshot snap, LocalDate date) {
        if (date.isBefore(LocalDate.now(CN))) {
            return false;
        }
        if (!beforeClose()) {
            return false;
        }
        return System.currentTimeMillis() - snap.computedAt > intradayTtlMs;
    }

    private void store(LocalDate date, Snapshot snap) {
        cache.put(date, snap);
        if (cache.size() <= maxCacheEntries) {
            return;
        }
        cache.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().computedAt))
                .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
    }

    private MarketSnapshotVO toVO(Snapshot snap, LocalDate date, boolean fromCache) {
        MarketSnapshotVO vo = new MarketSnapshotVO();
        vo.setTradeDate(date);
        vo.setPrevTradeDate(snap.prevTradeDate);
        vo.setSnapshotDate(snap.snapshotDate);
        vo.setLive(snap.live);
        vo.setFromCache(fromCache);
        vo.setFilled(snap.fields);
        vo.setMissing(missingOf(snap.fields));
        // 本次没走到报价那一步（历史日、或报价请求失败）就回读库里已存的档位：
        // 重算时打分读的就是库，卡面上显示的必须是同一份数，否则温度和 tooltip 会各说一套。
        MarketMetrics.PremiumTiers viewTiers = snap.premiumTiers != null
                ? snap.premiumTiers : premiumTierStore.read(date);
        PremiumTiersVO tiersView = tiersVO(date, viewTiers, snap.prevTradeDate);
        // 含首板整池只在这次真的拉过报价时才有：首板不入档位表，纯回读算不出来
        tiersView.setPooledPct(snap.fields.getYesterdayLimitPremium());
        vo.setPremiumTiers(tiersView);
        vo.setNotes(snap.notes);
        vo.setWarnings(snap.warnings);
        vo.setManualFields(MANUAL_FIELDS);
        // 读库而不是读这次取到几只：命中缓存时这次一只都没取，但库里可能已经五只都齐。
        vo.setIndexFilled(closedIndexCount(date));
        vo.setIndexTotal(tencent.expectedIndexCount());
        return vo;
    }

    // ---------- 杂项 ----------

    private static List<String> missingOf(MarketFields fields) {
        List<String> missing = new ArrayList<>();
        if (fields.getMaxConsecutiveLimit() == null) missing.add("maxConsecutiveLimit");
        if (fields.getLimitUpCount() == null) missing.add("limitUpCount");
        if (fields.getLimitDownCount() == null) missing.add("limitDownCount");
        if (fields.getYesterdayLimitPremium() == null) missing.add("yesterdayLimitPremium");
        if (fields.getBrokenBoardRate() == null) missing.add("brokenBoardRate");
        if (fields.getBigLossCount() == null) missing.add("bigLossCount");
        if (fields.getTotalVolume() == null) missing.add("totalVolume");
        return missing;
    }

    private static int missingCount(MarketFields fields) {
        return missingOf(fields).size();
    }

    /** 三个池都成功返回且都是空——上游超出可回溯范围时的典型形态。 */
    private static boolean allPoolsEmpty(PoolResult... pools) {
        for (PoolResult pool : pools) {
            if (pool == null || !pool.isOk() || pool.getTc() != 0) {
                return false;
            }
        }
        return true;
    }

    private static void warnIfTruncated(Snapshot snap, String label, PoolResult pool) {
        if (pool.isTruncated()) {
            snap.warnings.add(label + "被 pagesize 截断（上游 " + pool.getTc() + " 家、只取回 "
                    + pool.getRows().size() + " 家），依赖明细的数字可能偏低");
        }
    }

    private static String reason(PoolResult pool) {
        return pool == null ? "请求超时" : pool.getReason();
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private static boolean beforeClose() {
        LocalDateTime now = LocalDateTime.now(CN);
        return now.getHour() * 60 + now.getMinute() < CLOSE_MINUTE_OF_DAY;
    }

    /** 上一交易日涨停池，连同"是哪一天"——溢价必须能核对分母日期。 */
    private static final class PrevPool {
        private final LocalDate date;
        private final PoolResult result;

        private PrevPool(LocalDate date, PoolResult result) {
            this.date = date;
            this.result = result;
        }
    }

    private static final class Snapshot {
        private MarketFields fields;
        private LocalDate requestedDate;
        private LocalDate prevTradeDate;
        private LocalDate snapshotDate;
        /** 档位溢价：与 yesterdayLimitPremium 同一次报价算出来的分组结果，可能为 null（没走到那一步）。 */
        private MarketMetrics.PremiumTiers premiumTiers;
        private boolean live;
        private final long computedAt = System.currentTimeMillis();
        private final List<String> notes = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
    }
}
