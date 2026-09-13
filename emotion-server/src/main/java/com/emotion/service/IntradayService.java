package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.entity.StockConcept;
import com.emotion.entity.Theme;
import com.emotion.entity.ThemeStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.StockConceptMapper;
import com.emotion.mapper.ThemeMapper;
import com.emotion.mapper.ThemeStockMapper;
import com.emotion.vo.IntradayVO;
import com.emotion.vo.ThemeStockVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 日内核心·题材表：与板块表（行业，一票一行业）并列的第二张表。
 *
 * <p><b>板块 ≠ 题材</b>：题材是概念、一只票可归多个题材（如"人工智能"横跨多个行业）。
 * 因此用 {@code t_theme_stock} 存「题材→个股」关系（按日快照），题材计数只走 {@code is_primary=1}
 * 主题材去重，避免一票多题材把涨停数加爆。
 *
 * <p><b>概念归属回填</b>（{@link #backfill}）：涨停池 × 概念索引（{@code t_stock_concept}，
 * 由 {@link ConceptIndexService#rebuild} 建）求交，把当日涨停股按概念写成题材-个股关系。
 * 一票多概念用 is_primary 去重（取成员最多的概念计 1）。人工随后可在 /api/theme/stock/bind 微调主/辅。
 */
@Service
public class IntradayService {

    private static final Logger log = LoggerFactory.getLogger(IntradayService.class);

    /** 一字判定：首次封板 ≤ 09:30:00 且从未炸板。 */
    private static final int YIZI_SEAL_DEADLINE = 93000;
    /** 题材定义行里没有硬度时的默认值与 t_theme DDL DEFAULT 3 一致。 */
    private static final int DEFAULT_HARDNESS = 3;

    private final ThemeMapper themeMapper;
    private final ThemeStockMapper themeStockMapper;
    private final MarketStockMapper marketStockMapper;
    private final StockConceptMapper stockConceptMapper;

    public IntradayService(ThemeMapper themeMapper, ThemeStockMapper themeStockMapper,
                           MarketStockMapper marketStockMapper, StockConceptMapper stockConceptMapper) {
        this.themeMapper = themeMapper;
        this.themeStockMapper = themeStockMapper;
        this.marketStockMapper = marketStockMapper;
        this.stockConceptMapper = stockConceptMapper;
    }

    // ================= 自动回填 =================

    /** 主读取口：先幂等回填（当天总是重建，历史一次补上），再聚合。@Transactional 保证回填原子。 */
    @Transactional(rollbackFor = Exception.class)
    public IntradayVO themeTable(Long userId, LocalDate date) {
        backfill(userId, date);
        return aggregate(userId, date);
    }

    /**
     * 窗口内每个有涨停的行业自动登记成同名题材并绑定其涨停股（is_primary=1, source=AUTO）。
     * 当天 date 总是先删后插（盘面拉取会重写当天明细）；更早的日期只在从未回填过时补一次，
     * 避免每次读取都重写历史。幂等可重放。
     */
    @Transactional(rollbackFor = Exception.class)
    public int backfill(Long userId, LocalDate date) {
        if (userId == null || date == null) {
            return 0;
        }
        int built = rebuildAutoForDate(userId, date);
        LocalDate from = date.minusDays(PrdMetricsService.PERSISTENCE_WINDOW);
        List<LocalDate> dates = marketStockMapper.listDetailDatesBetween(from, date);
        for (LocalDate d : dates) {
            if (d.equals(date)) {
                continue;
            }
            if (themeStockMapper.countAuto(userId, d) == 0) {
                built += rebuildAutoForDate(userId, d);
            }
        }
        if (built > 0) {
            log.info("题材自动回填 user={} date={} 落 {} 条绑定", userId, date, built);
        }
        return built;
    }

    /** 单日重建：删该日 AUTO 行 → 涨停股按概念归属分组（索引来自 t_stock_concept，不联网）→ 缺题材先建 → 整批绑定。 */
    private int rebuildAutoForDate(Long userId, LocalDate date) {
        List<MarketStock> zt = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP));
        if (zt.isEmpty()) {
            return 0;
        }
        Map<String, MarketStock> byCode = new HashMap<>();
        Set<String> codes = new HashSet<>();
        for (MarketStock s : zt) {
            byCode.put(s.getCode(), s);
            codes.add(s.getCode());
        }
        // 一票多概念：涨停股可能属于多个概念，家里人概念分组
        List<StockConcept> rels = stockConceptMapper.selectList(new LambdaQueryWrapper<StockConcept>()
                .in(StockConcept::getCode, codes));
        Map<String, java.util.LinkedHashSet<String>> ztByConcept = new LinkedHashMap<>();
        for (StockConcept sc : rels) {
            ztByConcept.computeIfAbsent(sc.getConcept(), k -> new java.util.LinkedHashSet<>()).add(sc.getCode());
        }
        themeStockMapper.deleteAutoForDate(userId, date);
        List<ThemeStock> binds = new ArrayList<>();
        for (Map.Entry<String, java.util.LinkedHashSet<String>> e : ztByConcept.entrySet()) {
            Theme theme = ensureTheme(userId, e.getKey(), date);
            for (String code : e.getValue()) {
                MarketStock s = byCode.get(code);
                ThemeStock ts = new ThemeStock();
                ts.setUserId(userId);
                ts.setThemeId(theme.getId());
                ts.setTradeDate(date);
                ts.setCode(code);
                ts.setName(s == null ? code : s.getName());
                ts.setIndustry(s == null ? "" : s.getIndustry());
                ts.setIsPrimary(isPrimaryConcept(code, e.getKey(), ztByConcept) ? 1 : 0);
                ts.setSource("AUTO");
                binds.add(ts);
            }
        }
        if (!binds.isEmpty()) {
            themeStockMapper.insertBatch(binds);
        }
        return binds.size();
    }

    /** 一票多概念去重：某只涨停股只在一个概念里计数，选「涨停成员最多」的那个概念为 is_primary=1。 */
    private static boolean isPrimaryConcept(String code, String concept,
                                            Map<String, java.util.LinkedHashSet<String>> ztByConcept) {
        String best = null;
        int bestN = -1;
        for (Map.Entry<String, java.util.LinkedHashSet<String>> e : ztByConcept.entrySet()) {
            if (!e.getValue().contains(code)) {
                continue;
            }
            int n = e.getValue().size();
            if (n > bestN) {
                bestN = n;
                best = e.getKey();
            }
        }
        return concept.equals(best);
    }

    /** 按 (user, name) 找题材，不存在则建（硬度默认 3、状态萌芽、启动日今日）。题材无日粒度，最后一次写为准。 */
    private Theme ensureTheme(Long userId, String name, LocalDate date) {
        List<Theme> exists = themeMapper.selectList(new LambdaQueryWrapper<Theme>()
                .eq(Theme::getUserId, userId)
                .eq(Theme::getName, name)
                .orderByAsc(Theme::getId)
                .last("LIMIT 1"));
        if (!exists.isEmpty()) {
            return exists.get(0);
        }
        Theme t = new Theme();
        t.setUserId(userId);
        t.setName(name);
        t.setStartDate(date);
        t.setStatus("萌芽");
        t.setStrength(0);
        t.setCatalystHardness(DEFAULT_HARDNESS);
        t.setIsMainLine(0);
        themeMapper.insert(t);
        return t;
    }

    // ================= 聚合 =================

    /** 题材表聚合：按题材归并当日主题材股票，算强度，附未归类统计。 */
    public IntradayVO aggregate(Long userId, LocalDate date) {
        IntradayVO vo = new IntradayVO();
        vo.setDate(date);

        List<MarketStock> zt = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP));
        Map<String, MarketStock> byCode = new HashMap<>();
        for (MarketStock s : zt) {
            byCode.put(s.getCode(), s);
        }
        vo.setTotalZt(byCode.size());

        Map<Long, Theme> themes = new HashMap<>();
        for (Theme t : themeMapper.selectList(new LambdaQueryWrapper<Theme>()
                .eq(Theme::getUserId, userId))) {
            themes.put(t.getId(), t);
        }

        // 当日绑定行
        List<ThemeStock> today = themeStockMapper.selectList(new LambdaQueryWrapper<ThemeStock>()
                .eq(ThemeStock::getUserId, userId)
                .eq(ThemeStock::getTradeDate, date));
        // 持续天数：窗口内该题材每日主题材数
        Map<Long, TreeMap<LocalDate, Integer>> dailyPrimary = new HashMap<>();
        LocalDate from = date.minusDays(PrdMetricsService.PERSISTENCE_WINDOW);
        for (ThemeStock ts : themeStockMapper.selectList(new LambdaQueryWrapper<ThemeStock>()
                .eq(ThemeStock::getUserId, userId)
                .ge(ThemeStock::getTradeDate, from)
                .le(ThemeStock::getTradeDate, date))) {
            if (ts.getIsPrimary() == null || ts.getIsPrimary() != 1) {
                continue;
            }
            dailyPrimary.computeIfAbsent(ts.getThemeId(), k -> new TreeMap<>())
                    .merge(ts.getTradeDate(), 1, Integer::sum);
        }

        // 按题材分组当日主/辅
        Map<Long, List<ThemeStock>> byTheme = new LinkedHashMap<>();
        for (ThemeStock ts : today) {
            byTheme.computeIfAbsent(ts.getThemeId(), k -> new ArrayList<>()).add(ts);
        }

        List<IntradayVO.ThemeRow> rows = new ArrayList<>();
        Set<String> assignedCodes = new HashSet<>();
        for (Map.Entry<Long, List<ThemeStock>> e : byTheme.entrySet()) {
            Theme theme = themes.get(e.getKey());
            if (theme == null) {
                continue; // 题材定义被删了，关系留着但不展示
            }
            IntradayVO.ThemeRow row = buildRow(e.getValue(), byCode, theme.getName(),
                    theme.getCatalystHardness(), theme.getStatus(),
                    theme.getIsMainLine() != null && theme.getIsMainLine() == 1,
                    dailyPrimary.get(e.getKey()), date);
            for (ThemeStock ts : e.getValue()) {
                if (ts.getIsPrimary() != null && ts.getIsPrimary() == 1) {
                    assignedCodes.add(ts.getCode());
                }
            }
            rows.add(row);
        }
        vo.setAssigned(assignedCodes.size());
        vo.setUnassigned(Math.max(0, vo.getTotalZt() - vo.getAssigned()));

        // 强度归一化需要全局量：先算原始分，再封顶
        int globalH = 0;
        for (MarketStock s : zt) {
            int n = s.getConsecutive() == null ? 1 : s.getConsecutive();
            if (n > globalH) {
                globalH = n;
            }
        }
        int maxZt = rows.stream().mapToInt(r -> r.getZtCount()).max().orElse(1);
        double maxSeal = rows.stream().mapToDouble(r -> r.getSealSum() == null ? 0 : r.getSealSum().doubleValue())
                .max().orElse(1.0);

        for (IntradayVO.ThemeRow row : rows) {
            row.setStrength(calcThemeStrength(row.getZtCount(), row.getMaxBoard(),
                    row.getSealSum() == null ? 0 : row.getSealSum().doubleValue(),
                    row.getYiziCnt(), row.getBigLossCnt(), tierCount(row),
                    row.getHardness() == null ? DEFAULT_HARDNESS : row.getHardness(),
                    globalH, maxZt, maxSeal));
        }

        rows.sort(Comparator.comparingDouble(IntradayVO.ThemeRow::getStrength).reversed()
                .thenComparing(IntradayVO.ThemeRow::getMaxBoard, Comparator.reverseOrder())
                .thenComparing(IntradayVO.ThemeRow::getName));
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setRank(i + 1);
        }
        vo.setThemes(rows);
        return vo;
    }

    /** 把一个题材的全部绑定行加工成一行：只统计主题材；辅题材仅影响梯队展示。 */
    private IntradayVO.ThemeRow buildRow(List<ThemeStock> binds, Map<String, MarketStock> byCode,
                                         String themeName, Integer hardness, String status,
                                         boolean mainLine, TreeMap<LocalDate, Integer> dailyPrimary,
                                         LocalDate date) {
        IntradayVO.ThemeRow row = new IntradayVO.ThemeRow();
        row.setThemeId(binds.get(0).getThemeId());
        row.setName(themeName);
        row.setHardness(hardness == null ? DEFAULT_HARDNESS : hardness);
        row.setStatus(status == null ? "萌芽" : status);
        row.setMainLine(mainLine);

        int ztCnt = 0;
        int maxBoard = 0;
        BigDecimal sealSum = BigDecimal.ZERO;
        int yizi = 0;
        int bigLoss = 0;
        List<Integer> boards = new ArrayList<>();
        Set<String> industries = new java.util.TreeSet<>();
        MarketStock leader = null;
        for (ThemeStock ts : binds) {
            if (ts.getIsPrimary() == null || ts.getIsPrimary() != 1) {
                continue;
            }
            MarketStock s = byCode.get(ts.getCode());
            if (s == null) {
                continue;
            }
            ztCnt++;
            int b = s.getConsecutive() == null ? 1 : s.getConsecutive();
            if (b > maxBoard) {
                maxBoard = b;
                leader = s;
            } else if (b == maxBoard && leader != null && s.getChangePct() != null
                    && (leader.getChangePct() == null || s.getChangePct().compareTo(leader.getChangePct()) > 0)) {
                leader = s;
            }
            if (s.getSealAmount() != null) {
                sealSum = sealSum.add(s.getSealAmount());
            }
            if (isYizi(s)) {
                yizi++;
            }
            if (s.getBigLoss() != null && s.getBigLoss() == 1) {
                bigLoss++;
            }
            if (!boards.contains(b)) {
                boards.add(b);
            }
            if (s.getIndustry() != null && !s.getIndustry().isEmpty()) {
                industries.add(s.getIndustry());
            }
        }
        row.setZtCount(ztCnt);
        row.setMaxBoard(maxBoard);
        row.setSealSum(ztCnt == 0 ? BigDecimal.ZERO : sealSum);
        row.setYiziCnt(yizi);
        row.setBigLossCnt(bigLoss);
        boards.sort(Comparator.reverseOrder());
        row.setTierLevels(String.join(",", boards.stream().map(String::valueOf).toArray(String[]::new)));
        row.setIndustries(new ArrayList<>(industries));
        row.setContinuousDays(continuousDays(dailyPrimary, date));
        if (leader != null) {
            IntradayVO.Leader l = new IntradayVO.Leader();
            l.setCode(leader.getCode());
            l.setName(leader.getName());
            l.setBoard(leader.getConsecutive() == null ? 1 : leader.getConsecutive());
            l.setChangePct(leader.getChangePct());
            row.setLeader(l);
        }
        return row;
    }

    private static int tierCount(IntradayVO.ThemeRow row) {
        if (row.getTierLevels() == null || row.getTierLevels().isEmpty()) {
            return 0;
        }
        return row.getTierLevels().split(",").length;
    }

    private static boolean isYizi(MarketStock s) {
        return s.getFirstSealTime() != null && s.getFirstSealTime() <= YIZI_SEAL_DEADLINE
                && (s.getBreakCount() == null || s.getBreakCount() == 0);
    }

    /** 题材连续活跃天数：窗口内从 date 往回，每日主题材数 ≥1 才算活跃，逐日 +1。 */
    static int continuousDays(TreeMap<LocalDate, Integer> dailyPrimary, LocalDate date) {
        if (dailyPrimary == null) {
            return 0;
        }
        int days = 0;
        LocalDate cursor = date;
        for (int i = 0; i < PrdMetricsService.PERSISTENCE_WINDOW; i++) {
            Integer n = dailyPrimary.get(cursor);
            if (n == null || n < 1) {
                break;
            }
            days++;
            cursor = cursor.minusDays(1);
        }
        return days;
    }

    /**
     * 题材强度（五维 + 题材独有硬度加分 + 大面扣分）：与板块表同源公式，但数据源换成题材维度。
     * 涨停数 25% + 最高板 25%（用题材内最高板：跨行业也归一在题材内）+ 封单 20%（题材内封单合计）
     * + 一字占比 15% + 梯队完整 15%；催化剂硬度每比基准 3 高 1 加 5 分（题材特有），大面每只扣 10 分上限 30。
     */
    static double calcThemeStrength(int ztCount, int maxBoard, double sealSum,
                                    int yiziCnt, int bigLossCnt, int tierCnt,
                                    int hardness, int globalH, int maxZt, double maxSeal) {
        double cntScore = maxZt <= 0 ? 0 : Math.min(100, ztCount * 100.0 / maxZt);
        double hScore = globalH <= 0 ? 0 : Math.min(100, maxBoard * 100.0 / globalH);
        double sealScore = maxSeal <= 0 ? 0 : Math.min(100, sealSum * 100.0 / maxSeal);
        double yiziScore = ztCount <= 0 ? 0 : (yiziCnt * 100.0 / ztCount);
        int should = Math.max(maxBoard - 1, 1);
        double tierScore = Math.min(100, tierCnt * 100.0 / should);
        double hardBonus = (hardness - DEFAULT_HARDNESS) * 5.0;
        double penalty = Math.min(30, bigLossCnt * 10.0);
        double v = 0.25 * cntScore + 0.25 * hScore + 0.20 * sealScore
                + 0.15 * yiziScore + 0.15 * tierScore + hardBonus - penalty;
        return Math.max(0, Math.min(100, Math.round(v * 100.0) / 100.0));
    }

    // ================= 下钻 / 人工归类 =================

    /** 题材梯队：该题材当日全部绑定个股（主+辅）按连板降序分层。 */
    public List<ThemeStockVO.Tier> tiers(Long userId, Long themeId, LocalDate date) {
        List<ThemeStock> binds = themeStockMapper.selectList(new LambdaQueryWrapper<ThemeStock>()
                .eq(ThemeStock::getUserId, userId)
                .eq(ThemeStock::getThemeId, themeId)
                .eq(ThemeStock::getTradeDate, date));
        List<MarketStock> zt = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP));
        Map<String, MarketStock> byCode = new HashMap<>();
        for (MarketStock s : zt) {
            byCode.put(s.getCode(), s);
        }
        Map<Integer, List<ThemeStockVO.StockLine>> byBoard = new java.util.TreeMap<>(Comparator.reverseOrder());
        for (ThemeStock ts : binds) {
            MarketStock s = byCode.get(ts.getCode());
            int b = s == null || s.getConsecutive() == null ? 0 : s.getConsecutive();
            byBoard.computeIfAbsent(b, k -> new ArrayList<>()).add(line(ts, s));
        }
        List<ThemeStockVO.Tier> out = new ArrayList<>();
        for (Map.Entry<Integer, List<ThemeStockVO.StockLine>> e : byBoard.entrySet()) {
            ThemeStockVO.Tier tier = new ThemeStockVO.Tier();
            tier.setBoard(e.getKey());
            e.getValue().sort(Comparator.comparing((ThemeStockVO.StockLine l) -> l.isPrimary() ? 0 : 1)
                    .thenComparing(ThemeStockVO.StockLine::getCode));
            tier.setStocks(e.getValue());
            out.add(tier);
        }
        return out;
    }

    private ThemeStockVO.StockLine line(ThemeStock ts, MarketStock s) {
        ThemeStockVO.StockLine l = new ThemeStockVO.StockLine();
        l.setCode(ts.getCode());
        l.setName(s == null ? ts.getName() : s.getName());
        l.setIndustry(ts.getIndustry());
        l.setBoard(s == null || s.getConsecutive() == null ? null : s.getConsecutive());
        l.setChangePct(s == null ? null : s.getChangePct());
        l.setSealAmount(s == null ? null : s.getSealAmount());
        l.setYizi(s != null && isYizi(s));
        l.setPrimary(ts.getIsPrimary() != null && ts.getIsPrimary() == 1);
        return l;
    }

    /** 题材→板块映射：该题材主题材个股横跨的行业+计数（体现"跨行业"价值）。 */
    public List<ThemeStockVO.IndustryCount> industries(Long userId, Long themeId, LocalDate date) {
        List<ThemeStock> binds = themeStockMapper.selectList(new LambdaQueryWrapper<ThemeStock>()
                .eq(ThemeStock::getUserId, userId)
                .eq(ThemeStock::getThemeId, themeId)
                .eq(ThemeStock::getTradeDate, date));
        Map<String, Integer> byInd = new LinkedHashMap<>();
        for (ThemeStock ts : binds) {
            if (ts.getIsPrimary() == null || ts.getIsPrimary() != 1) {
                continue;
            }
            String ind = ts.getIndustry();
            if (ind == null || ind.isEmpty()) {
                continue;
            }
            byInd.merge(ind, 1, Integer::sum);
        }
        List<ThemeStockVO.IndustryCount> out = new ArrayList<>();
        byInd.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    ThemeStockVO.IndustryCount ic = new ThemeStockVO.IndustryCount();
                    ic.setIndustry(e.getKey());
                    ic.setCount(e.getValue());
                    out.add(ic);
                });
        return out;
    }

    /** 未归类：当日全市场涨停股里没出现在任何题材绑定里的独立股票。 */
    public List<ThemeStockVO.StockLine> unassigned(Long userId, LocalDate date) {
        List<ThemeStock> binds = themeStockMapper.selectList(new LambdaQueryWrapper<ThemeStock>()
                .eq(ThemeStock::getUserId, userId)
                .eq(ThemeStock::getTradeDate, date));
        Set<String> bound = new HashSet<>();
        for (ThemeStock ts : binds) {
            bound.add(ts.getCode());
        }
        List<MarketStock> zt = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP));
        List<ThemeStockVO.StockLine> out = new ArrayList<>();
        for (MarketStock s : zt) {
            if (bound.contains(s.getCode())) {
                continue;
            }
            ThemeStock stub = new ThemeStock();
            stub.setCode(s.getCode());
            stub.setName(s.getName());
            stub.setIndustry(s.getIndustry());
            out.add(line(stub, s));
        }
        out.sort(Comparator.comparing((ThemeStockVO.StockLine l) -> l.getBoard() == null ? 0 : l.getBoard(),
                Comparator.reverseOrder()).thenComparing(ThemeStockVO.StockLine::getCode));
        return out;
    }

    /**
     * 人工归类（幂等）：把若干 code 设进某题材某日。先删该主题日该码旧行再见插，
     * 保证切主题材/辅题材/换题材不残留。name/industry 从当日涨停池补齐。
     */
    @Transactional(rollbackFor = Exception.class)
    public int bind(Long userId, Long themeId, LocalDate date, List<String> codes, boolean primary) {
        Theme theme = themeMapper.selectOne(new LambdaQueryWrapper<Theme>()
                .eq(Theme::getId, themeId).eq(Theme::getUserId, userId));
        if (theme == null) {
            throw new IllegalArgumentException("题材不存在");
        }
        if (codes == null || codes.isEmpty()) {
            return 0;
        }
        Set<String> codeSet = new HashSet<>(codes);
        List<MarketStock> zt = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP));
        Map<String, MarketStock> byCode = new HashMap<>();
        for (MarketStock s : zt) {
            byCode.put(s.getCode(), s);
        }
        int done = 0;
        for (String code : codeSet) {
            themeStockMapper.delete(new LambdaQueryWrapper<ThemeStock>()
                    .eq(ThemeStock::getUserId, userId)
                    .eq(ThemeStock::getThemeId, themeId)
                    .eq(ThemeStock::getTradeDate, date)
                    .eq(ThemeStock::getCode, code));
            MarketStock s = byCode.get(code);
            ThemeStock ts = new ThemeStock();
            ts.setUserId(userId);
            ts.setThemeId(themeId);
            ts.setTradeDate(date);
            ts.setCode(code);
            ts.setName(s == null ? code : s.getName());
            ts.setIndustry(s == null ? "" : s.getIndustry());
            ts.setIsPrimary(primary ? 1 : 0);
            ts.setSource("MANUAL");
            themeStockMapper.insert(ts);
            done++;
        }
        return done;
    }
}