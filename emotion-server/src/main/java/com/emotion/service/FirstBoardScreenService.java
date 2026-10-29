package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.FirstBoardScreen;
import com.emotion.entity.MarketStock;
import com.emotion.entity.StockConcept;
import com.emotion.mapper.FirstBoardScreenMapper;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.StockConceptMapper;
import com.emotion.market.TencentClient;
import com.emotion.market.TencentClient.DayBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首板筛选器：当天涨停的首板里，哪些<b>站上 250 日均线</b>、且它的通达信题材
 * <b>在当日连板天梯里一只都没有</b>。
 *
 * <p>两条判据的分工是刻意的：年线说的是「这只票自己的长期位置」，题材在天梯里没有说的是
 * 「它所在的题材还没有梯队」。合起来就是资金在一个<b>长期趋势之上、当天无人接力</b>的方向上
 * 打新板——这是首板里最值得单独盯的那一批。
 *
 * <p>题材口径按他定的走通达信<b>概念板块</b>（{@code t_stock_concept}，V33 那份种子），
 * 不是引擎分组用的二级行业：一只票常挂 1~11 个概念，只要<b>任一</b>概念被当天某只 2 板及以上的票
 * 沾上就判「题材在天梯里」。因此这条闸比按行业判严得多，一天剩 0~3 只是常态而不是异常。
 *
 * <p>天梯 = 当日涨停池 {@code consecutive>=2}（与 {@code TiantiService} 铺层的口径同一把尺，
 * 那里层是从当日最高板铺到 2 板）。
 *
 * <p><b>先算便宜的、后算贵的</b>：题材判定只查一次库，年线每只要拉一年日 K。
 * 所以只有题材空白的那几只才会去打上游——把一天几十次往返压到个位数。
 * 代价是同时踩中两条的票只报第一个失败原因（{@code THEME_IN_LADDER}），
 * 它的年线格留空。顺序固定，不随当日数据变。
 */
@Service
public class FirstBoardScreenService {

    private static final Logger log = LoggerFactory.getLogger(FirstBoardScreenService.class);

    /** 年线根数：含当日那根收盘。 */
    static final int ANNUAL_LINE_BARS = 250;
    /**
     * 取日 K 往前给的自然日：250 个交易日约 375 个自然日，余量留给停牌与长假。
     * 给少了会让一只停牌不多的票判成「没有年线」，那是判据错了而不是数据缺。
     */
    private static final int LOOKBACK_DAYS = 400;

    private final MarketStockMapper marketStockMapper;
    private final StockConceptMapper stockConceptMapper;
    private final FirstBoardScreenMapper screenMapper;
    private final DailyBarService dailyBarService;
    private final IndustryClassifyService industryClassify;

    public FirstBoardScreenService(MarketStockMapper marketStockMapper,
                                   StockConceptMapper stockConceptMapper,
                                   FirstBoardScreenMapper screenMapper,
                                   DailyBarService dailyBarService,
                                   IndustryClassifyService industryClassify) {
        this.marketStockMapper = marketStockMapper;
        this.stockConceptMapper = stockConceptMapper;
        this.screenMapper = screenMapper;
        this.dailyBarService = dailyBarService;
        this.industryClassify = industryClassify;
    }

    /**
     * 判一天并整日替换落库。当天涨停池没落库时<b>直接返回空、一个字都不写</b>：
     * 清掉旧行会被界面读成「今天判定过、一只都没过」，那是假答案。
     */
    public List<FirstBoardScreen> screen(LocalDate date) {
        List<MarketStock> zt = listPool(date, MarketStock.POOL_LIMIT_UP);
        if (zt.isEmpty()) {
            log.info("{} 涨停池无明细，首板筛选不写（保留旧行）", date);
            return new ArrayList<>();
        }

        List<MarketStock> firsts = new ArrayList<>();
        List<String> ladderCodes = new ArrayList<>();
        for (MarketStock m : zt) {
            int n = m.getConsecutive() == null ? 1 : m.getConsecutive();
            if (n == 1) {
                firsts.add(m);
            } else {
                ladderCodes.add(m.getCode());
            }
        }
        if (firsts.isEmpty()) {
            // 当天有涨停明细但一只首板都没有：判定确实是「空」，把旧行清掉不留假名单
            screenMapper.deleteByDate(date);
            return new ArrayList<>();
        }

        // 一次 IN 查询拿全「首板 + 天梯」的概念行，天梯侧与首板侧共用这一份
        List<String> codes = new ArrayList<>();
        for (MarketStock m : firsts) {
            codes.add(m.getCode());
        }
        codes.addAll(ladderCodes);
        Map<String, List<String>> conceptsByCode = loadConcepts(codes);
        Set<String> ladderConcepts = new HashSet<>();
        for (String code : ladderCodes) {
            ladderConcepts.addAll(conceptsByCode.getOrDefault(code, Collections.emptyList()));
        }

        List<FirstBoardScreen> rows = new ArrayList<>();
        for (MarketStock m : firsts) {
            List<String> own = conceptsByCode.getOrDefault(m.getCode(), Collections.emptyList());
            // 只有题材空白者才值得为它打一趟上游
            List<BigDecimal> prior = blankTheme(own, ladderConcepts)
                    ? annualCloses(m.getCode(), date)
                    : null;
            rows.add(evaluate(m, own, ladderConcepts, prior));
        }
        rows.sort(Comparator.comparing(FirstBoardScreen::getPassed).reversed()
                .thenComparing(FirstBoardScreen::getCode));

        replace(date, rows);
        int passed = 0;
        for (FirstBoardScreen r : rows) {
            if (Boolean.TRUE.equals(r.getPassed())) {
                passed++;
            }
        }
        log.info("{} 首板筛选：{} 只首板，天梯覆盖 {} 个题材，通过 {} 只",
                date, rows.size(), ladderConcepts.size(), passed);
        return rows;
    }

    /** 当日留档（含未通过的）。读侧不打上游、不重算。 */
    public List<FirstBoardScreen> list(LocalDate date) {
        return date == null ? new ArrayList<>() : screenMapper.listByDate(date);
    }

    /** 当日通过两条判据的首板：D9 候选池的取数口。 */
    public List<FirstBoardScreen> listPassed(LocalDate date) {
        return date == null ? new ArrayList<>() : screenMapper.listPassedByDate(date);
    }

    // ------------------------------------------------------------------ 判据

    /** 题材空白＝这只票有概念可判，且没有一个概念出现在天梯题材并集里。 */
    private static boolean blankTheme(List<String> own, Set<String> ladderConcepts) {
        return !own.isEmpty() && overlap(own, ladderConcepts) == 0;
    }

    private static int overlap(List<String> own, Set<String> ladderConcepts) {
        int n = 0;
        for (String c : own) {
            if (ladderConcepts.contains(c)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 一只首板的判定，<b>纯函数</b>：只吃已经取好的数据，不碰库也不碰网络，单测直接喂样本。
     *
     * @param own            该股所属通达信题材简称（可为空＝索引里没有）
     * @param ladderConcepts 当日天梯（2 板及以上）覆盖的题材并集
     * @param priorCloses    严格早于当天的收盘价，<b>按日期升序</b>；题材未空白时传 {@code null}
     */
    static FirstBoardScreen evaluate(MarketStock m, List<String> own, Set<String> ladderConcepts,
                                     List<BigDecimal> priorCloses) {
        FirstBoardScreen r = new FirstBoardScreen();
        r.setTradeDate(m.getTradeDate());
        r.setCode(m.getCode());
        r.setName(m.getName());
        r.setIndustry(m.getIndustry());
        r.setConcepts(String.join(",", own));
        r.setConceptCount(own.size());
        r.setLadderConcepts(ladderConcepts.size());
        r.setClosePrice(m.getClosePrice());
        r.setPassed(false);

        if (own.isEmpty()) {
            r.setOverlapCount(0);
            r.setReason(FirstBoardScreen.REASON_NO_CONCEPT);
            return r;
        }
        int hit = overlap(own, ladderConcepts);
        r.setOverlapCount(hit);
        if (hit > 0) {
            r.setReason(FirstBoardScreen.REASON_THEME_IN_LADDER);
            return r;
        }
        if (m.getClosePrice() == null) {
            r.setReason(FirstBoardScreen.REASON_NO_CLOSE);
            return r;
        }
        BigDecimal ma = annualLine(priorCloses, m.getClosePrice());
        if (ma == null) {
            r.setBarsUsed(priorCloses == null ? 0 : Math.min(priorCloses.size(), ANNUAL_LINE_BARS - 1) + 1);
            r.setReason(FirstBoardScreen.REASON_NO_ANNUAL_LINE);
            return r;
        }
        BigDecimal close = m.getClosePrice();
        r.setMa250(ma);
        r.setBarsUsed(ANNUAL_LINE_BARS);
        r.setAbovePct(close.subtract(ma).multiply(BigDecimal.valueOf(100))
                .divide(ma, 2, RoundingMode.HALF_UP));
        boolean above = close.compareTo(ma) > 0;
        r.setPassed(above);
        r.setReason(above ? FirstBoardScreen.REASON_PASS : FirstBoardScreen.REASON_BELOW_MA250);
        return r;
    }

    /**
     * 年线：升序收盘里取最近 {@value #ANNUAL_LINE_BARS} − 1 根，加上当日收盘凑满 250 根求均值。
     *
     * <p>当日那根只能从涨停池的 {@code close_price} 拿：15:30 时腾讯个股日线通常还没发布当天
     * （V31 实测只到 T-3，日 K 缓存对最近三天刻意不留痕），所以年线一律是
     * 「249 根已发布 + 当日真实收盘」。不足 250 根时返回 {@code null} 而不是按现有根数平均——
     * 拿 60 根算出来的东西标成 250 日均线，是造一个假判据。
     */
    static BigDecimal annualLine(List<BigDecimal> priorCloses, BigDecimal todayClose) {
        int need = ANNUAL_LINE_BARS - 1;
        if (todayClose == null || priorCloses == null || priorCloses.size() < need) {
            return null;
        }
        List<BigDecimal> tail = priorCloses.subList(priorCloses.size() - need, priorCloses.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal c : tail) {
            sum = sum.add(c);
        }
        sum = sum.add(todayClose);
        return sum.divide(BigDecimal.valueOf(ANNUAL_LINE_BARS), 3, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ 取数

    /** 一批代码 → 通达信题材简称列表。索引是静态的（无 trade_date 列），一天查一次即可。 */
    private Map<String, List<String>> loadConcepts(List<String> codes) {
        Map<String, List<String>> out = new HashMap<>();
        if (codes.isEmpty()) {
            return out;
        }
        List<StockConcept> rows = stockConceptMapper.selectList(
                new LambdaQueryWrapper<StockConcept>().in(StockConcept::getCode, codes)
                        .orderByAsc(StockConcept::getCode));
        for (StockConcept row : rows) {
            String name = row.getConceptCode() == null ? row.getConcept() : row.getConceptCode();
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            out.computeIfAbsent(row.getCode(), k -> new ArrayList<>()).add(name.trim());
        }
        // 一只票的概念顺序按代码去重后可能重复登记，去重保序，免得交集计数虚高
        for (Map.Entry<String, List<String>> e : out.entrySet()) {
            e.setValue(new ArrayList<>(new LinkedHashSet<>(e.getValue())));
        }
        return out;
    }

    /** 严格早于当天的已发布收盘，升序。取不到返回空列表（判成「没有年线」，不猜）。 */
    private List<BigDecimal> annualCloses(String code, LocalDate date) {
        List<BigDecimal> closes = new ArrayList<>();
        List<DayBar> bars;
        try {
            bars = dailyBarService.bars(TencentClient.symbolOf(code),
                    date.minusDays(LOOKBACK_DAYS), date);
        } catch (RuntimeException e) {
            log.warn("首板筛选取日 K 失败 code={} date={}：{}", code, date, e.toString());
            return closes;
        }
        if (bars == null) {
            return closes;
        }
        for (DayBar b : bars) {
            if (b.getDate() != null && b.getDate().isBefore(date) && b.getClose() != null) {
                closes.add(b.getClose());
            }
        }
        return closes;
    }

    private List<MarketStock> listPool(LocalDate date, String pool) {
        List<MarketStock> rows = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, pool));
        industryClassify.apply(rows);
        return rows;
    }

    /** 整日替换：先删当天再整批插。与候选池同一做法（同一天重跑判定，行数不该累积）。 */
    private void replace(LocalDate date, List<FirstBoardScreen> rows) {
        screenMapper.deleteByDate(date);
        if (!rows.isEmpty()) {
            screenMapper.insertBatch(rows);
        }
    }
}
