package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MainlineMark;
import com.emotion.entity.MarketStock;
import com.emotion.entity.Theme;
import com.emotion.mapper.MainlineMarkMapper;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.ThemeMapper;

/**
 * PRD 2.0（five_dim_v2）新增维度的「自动取数聚合器」：
 * 从 {@code t_market_stock}(涨停/炸板池,含 amount 成交额) 与 {@code t_theme} 推导
 * 主线 5 要素（zt_gather_pct / height_gather_pct / amount_gather_pct / catalyst_hardness / persistence_days），
 * 以及 D2 结构控制量
 * （{@link #METRIC_MAIN_ACTIVE} / {@link #METRIC_SPACE_IN_MAIN} / {@link #METRIC_STAGE_CAP} /
 * {@link #METRIC_DRAGON_MISALIGN}）供引擎做催化剂缺省、空间板归属、生命周期天花板与龙头错位信号。
 *
 * <p>2026-09-12 D5 融合后，旧龙头分工五键（dragon_zong_long 等）不再写进 metrics：D5 阵眼个体改由
 * {@code HighEcoMetricsService} 基于人工 {@code t_anchor} 计算；本类的总龙/中军/跟风/卡位/反包
 * 快照字段保留，供天梯页与主线详情页展示、以及生命周期/轮动信号内部判定。
 *
 * <p>与 {@link LadderMetricsService} 同一哲学：<b>取不到的键不进 metrics（=未评），绝不兜 0</b>。
 * 主线板块判定：当日涨停聚集度（行业涨停家数 / 全市场涨停家数）最高的行业（东财 hybk 字段，industry≠题材，
 * 但"今天钱在哪个方向"的客观旁证与题材名一致时可对上 t_theme 的硬度/阶段）。
 *
 * <p>DB 读取集中在 {@link #snapshot}；{@link #aggregate} 是纯函数（连 persistence 历史与题材行都作参数传入），
 * 单测直接喂内存 fixture。快照同时供连板天梯（龙头标签）、首板池、主线详情页复用，避免三处各算一遍。
 */
@Service
public class PrdMetricsService {

    private static final Logger log = LoggerFactory.getLogger(PrdMetricsService.class);

    /**
     * 主线"一个热度交易日"的门槛：当日该行业涨停 ≥5 家（2026-09-10 起从 3 收紧到 5）。
     * 日内最热行业每天都有（=日内核心），但只有连续 3 个热度交易日（含今天，即 persistenceDays≥3）
     * 才被收集为「主线龙头」。
     */
    static final int HOT_ZT_THRESHOLD = 5;
    /** 轮动信号「新题材种子」的门槛维持 3 家：种子本来就是早期预警，比主线确认松一档。 */
    static final int SEED_ZT_THRESHOLD = 3;
    /** 连续多少个热度交易日才收集为主线龙头（含今天）。 */
    static final int MAINLINE_CONFIRM_DAYS = 3;
    /** 持续性回看的最大窗口（交易日，按 t_market_stock 已落库日期计）。 */
    static final int PERSISTENCE_WINDOW = 30;

    // ---- 喂给 BoardScoreCalculator 的 D2 结构键（metrics 里的控制量，不是读数阶梯）----
    /** 日内核心存在（当天有涨停池且选出了最热行业）：催化剂缺省 50 的前置闸门，防止无涨停日凭空出分。 */
    static final String METRIC_MAIN_ACTIVE = "main_sector_active";
    /** 全市场空间板 H 是否落在主线行业（1/0）：高度聚集度"空间板归属"口径的判据。 */
    static final String METRIC_SPACE_IN_MAIN = "space_board_in_main";
    /** 生命周期阶段对应的 D2 分数天花板（萌芽50/确认70/扩散85/亢奋100/退潮30）。 */
    static final String METRIC_STAGE_CAP = "mainline_stage_cap";
    /** 总龙头不属于日内核心板块（1=错位无合力）：D2 ×0.9。 */
    static final String METRIC_DRAGON_MISALIGN = "dragon_misalign";

    /**
     * 生命周期阶段 → D2 主线明确度天花板：萌芽期主线还没被确认，再强的单日聚集也不许越过 50；
     * 退潮 30、确认 70、扩散 85、亢奋 100。引擎读 {@link #METRIC_STAGE_CAP} 做封顶，阶段判定在本服务。
     */
    static final Map<String, Integer> STAGE_CAP;
    static {
        Map<String, Integer> cap = new LinkedHashMap<String, Integer>();
        cap.put("萌芽", 50);
        cap.put("确认", 70);
        cap.put("扩散", 85);
        cap.put("亢奋", 100);
        cap.put("退潮", 30);
        STAGE_CAP = Collections.unmodifiableMap(cap);
    }

    private final MarketStockMapper marketStockMapper;
    private final ThemeMapper themeMapper;
    private final MainlineMarkMapper mainlineMarkMapper;

    public PrdMetricsService(MarketStockMapper marketStockMapper, ThemeMapper themeMapper,
                             MainlineMarkMapper mainlineMarkMapper) {
        this.marketStockMapper = marketStockMapper;
        this.themeMapper = themeMapper;
        this.mainlineMarkMapper = mainlineMarkMapper;
    }

    /** 一次快照：metrics 喂打分引擎，其余字段喂天梯/首板/主线详情页。 */
    public static class Snapshot {
        public String mainIndustry;            // 主线行业（涨停聚集度最高），无涨停=null
        public int ztTotal;
        public int zbTotal;
        public int mainZt;
        public int maxBoard;                   // 全市场最高连板 H
        public int mainMaxBoard;               // 主线最高连板
        public Double ztGatherPct;             // 涨停聚集度 %
        public Double heightGatherPct;         // 高度聚集度 %
        public Double amountGatherPct;         // 成交额聚集度 %（涨停池内资金板块占比，amount 全缺=null）
        public boolean spaceBoardInMain;       // 全市场空间板 H 是否落在主线行业
        public boolean dragonAligned;          // 今日总龙头是否属于主线行业（false=龙头与主线错位）
        public MarketStock mainLeader;         // 主线行业内最高板（板块内龙头；可与全市场总龙头不是同一只）
        public String lifecycleStage;          // 生命周期阶段：萌芽/确认/扩散/亢奋/退潮（无主线=null）
        public Integer persistenceDays;        // 连续活跃天数（当日不活跃=0）
        public Theme mainTheme;                // 名称与主线行业一致的题材行（可 null）
        public MarketStock zongLong;           // 总龙头=全市场最高连板（可 null）
        public boolean zongLongPromoted;       // 总龙头是否晋级（昨 H-1 今 H）
        public String zongLongAction;          // PROMOTE/HOLD/BREAK/ABSENT
        /** 总龙头判定依据（人话）：选取规则 + 同板高 tie-break + 今日状态证据。 */
        public String dragonReason;
        /** 主线是否成立（评分对象已确认）：人工标记或自动主线（连续≥3 热度日）。连续天数口径见 {@link #consecutiveDays}，今天 3–4 仍活跃不算断。 */
        public boolean mainlineConfirmed;
        /** D2 评分对象是否由人工主线标记（t_mainline_mark）产生。 */
        public boolean manuallyMarked;
        /** 当日涨停聚集度最高行业（候选榜首，雷达区第0行）；无涨停=null。 */
        public String radarTopIndustry;
        /** 自动主线行业（radar 中连续≥3天最强者）；无=null。 */
        public String autoMainlineIndustry;
        /** 雷达区板块表（候选池）：当日所有有涨停的行业，按 zt 降序 → 持续天数降序。 */
        public List<RadarRow> radar = new ArrayList<>();
        /** 雷达区题材表：把 radar 按题材归并（共享同一 t_theme 的行业合并成一行），只含用户已登记题材的行业。 */
        public List<RadarRow> radarThemes = new ArrayList<>();
        public List<MarketStock> zhongJun = new ArrayList<>();  // 主线内其余连板≥2（中军候选）
        public int genFengCount;               // 主线内跟风涨停家数（扣掉总龙/中军）
        public MarketStock kaWei;              // 他题材最高标（封住）/昨日他题材高标今炸
        public boolean kaWeiSealed;
        public List<MarketStock> fanBao = new ArrayList<>();    // 昨炸板今回封
        public Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        public List<String> rotationSignals = new ArrayList<>();
    }

    /** 雷达区单行（候选池）：当日一个行业板块的快照，不打 D2 分，只标连续天数与强度。 */
    public static class RadarRow {
        public String industry;
        /** 该行业名匹配到的用户题材（t_theme.name==industry，可能 null=未登记题材）。 */
        public String theme;
        public int zt;                 // 当日涨停家数
        public int maxBoard;           // 板块内最高连板
        public int persistenceDays;    // 连续热度天数（当日该行业 ZT≥5 往前数）
        public String flag;            // NEW(1天🆕)/WATCH(2天)/MAIN(≥3天⭐)
        public boolean isMainline;     // persistenceDays >= MAINLINE_CONFIRM_DAYS
        public MarketStock leader;     // 板块内最高板（可 null）
        public double ztGatherPct;     // 板块涨停聚集度 %（本行业/全市场）
    }

    /** 读当天+前一交易日的池明细、主线活跃历史与题材行，产出快照。DB 异常向上抛，调用方决定降级方式。 */
    public Snapshot snapshot(Long userId, LocalDate date) {
        List<MarketStock> todayZT = listPool(date, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> todayZB = listPool(date, MarketStock.POOL_BROKEN);
        LocalDate prev = marketStockMapper.prevDetailDate(date);
        List<MarketStock> prevZT = prev == null ? Collections.<MarketStock>emptyList()
                : listPool(prev, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> prevZB = prev == null ? Collections.<MarketStock>emptyList()
                : listPool(prev, MarketStock.POOL_BROKEN);

        // 主线活跃历史（persistence 用）：近窗口内该行业每日涨停家数。先粗取行业再查会多一趟 SQL，
        // 这里直接一次拉全行业 ZT 行按 (date,industry) 分组，量大也就一个月 × 两三百行。
        Map<LocalDate, Map<String, Integer>> dailyIndustryZt = new LinkedHashMap<>();
        LocalDate windowStart = date.minusDays(PERSISTENCE_WINDOW);
        List<MarketStock> window = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP)
                .ge(MarketStock::getTradeDate, windowStart)
                .le(MarketStock::getTradeDate, date));
        for (MarketStock row : window) {
            String ind = row.getIndustry();
            if (ind == null || ind.trim().isEmpty()) {
                continue;
            }
            Map<String, Integer> byIndustry = dailyIndustryZt.get(row.getTradeDate());
            if (byIndustry == null) {
                byIndustry = new HashMap<String, Integer>();
                dailyIndustryZt.put(row.getTradeDate(), byIndustry);
            }
            Integer n = byIndustry.get(ind);
            byIndustry.put(ind, n == null ? 1 : n + 1);
        }

        // 题材行（用户自维护）：名称与行业一致才算对上；同账号当天只可能有一行命中（重名取最新）。
        // userId 为 null（理论不该发生）时跳过，硬度=未评。
        Theme mainTheme = null;
        Map<String, Theme> industryTheme = new HashMap<String, Theme>();
        try {
            List<Theme> themes = themeMapper.selectList(new LambdaQueryWrapper<Theme>()
                    .eq(userId != null, Theme::getUserId, userId)
                    .orderByDesc(Theme::getCreatedAt));
            for (Theme t : themes) {
                if (t.getName() == null || t.getName().trim().isEmpty()) {
                    continue;
                }
                String name = t.getName().trim();
                // 雷达区题材关联表：行业名 → 题材行（同名取最新）。
                Theme existing = industryTheme.get(name);
                if (existing == null || (t.getCreatedAt() != null && (existing.getCreatedAt() == null
                        || t.getCreatedAt().isAfter(existing.getCreatedAt())))) {
                    industryTheme.put(name, t);
                }
                if (mainTheme == null || t.getCreatedAt() == null || mainTheme.getCreatedAt() == null
                        || t.getCreatedAt().isAfter(mainTheme.getCreatedAt())) {
                    mainTheme = t; // 先记最新一行，aggregate 里按主线行业名匹配后使用
                }
            }
            // 上面拿的是"最新题材"，但主线可能对不上它；精确匹配交给 aggregate（需要主线名）。
            // 为保持 aggregate 纯函数，这里把"同名行"挑出来，找不到再退最新行供主线页展示。
        } catch (RuntimeException e) {
            log.warn("题材行读取失败 user={} date={} 原因={}（催化剂硬度未评）", userId, date, e.toString());
        }

        // 人工主线标记（t_mainline_mark）：当日命中则作为 D2 评分对象的最高优先（高于 ≥3天 自动主线）。
        String manualMainline = null;
        if (mainlineMarkMapper != null && userId != null) {
            try {
                List<MainlineMark> marks = mainlineMarkMapper.selectList(new LambdaQueryWrapper<MainlineMark>()
                        .eq(MainlineMark::getUserId, userId)
                        .eq(MainlineMark::getTradeDate, date)
                        .orderByDesc(MainlineMark::getUpdatedAt));
                if (!marks.isEmpty()) {
                    manualMainline = marks.get(0).getIndustry();
                }
            } catch (RuntimeException e) {
                log.warn("人工主线标记读取失败 user={} date={} 原因={}（按无人工标记处理）", userId, date, e.toString());
            }
        }

        return aggregate(date, todayZT, todayZB, prevZT, prevZB, dailyIndustryZt, userId, mainTheme,
                manualMainline, industryTheme);
    }

    /**
     * 7 参纯聚合（不碰 DB），保留给既有单测与无人工标记场景；委托 8 参核心（manualMainlineIndustry=null）。
     */
    Snapshot aggregate(LocalDate date,
                       List<MarketStock> todayZT, List<MarketStock> todayZB,
                       List<MarketStock> prevZT, List<MarketStock> prevZB,
                       Map<LocalDate, Map<String, Integer>> dailyIndustryZt,
                       Long userId, Theme mainTheme) {
        return aggregate(date, todayZT, todayZB, prevZT, prevZB, dailyIndustryZt, userId, mainTheme,
                null, Collections.<String, Theme>emptyMap());
    }

    /** 9 参纯聚合：人工主线标记场景（无题材关联表）；委托 10 参核心。 */
    Snapshot aggregate(LocalDate date,
                       List<MarketStock> todayZT, List<MarketStock> todayZB,
                       List<MarketStock> prevZT, List<MarketStock> prevZB,
                       Map<LocalDate, Map<String, Integer>> dailyIndustryZt,
                       Long userId, Theme mainTheme, String manualMainlineIndustry) {
        return aggregate(date, todayZT, todayZB, prevZT, prevZB, dailyIndustryZt, userId, mainTheme,
                manualMainlineIndustry, Collections.<String, Theme>emptyMap());
    }

    /**
     * 纯聚合（不碰 DB）。dailyIndustryZt：日期→(行业→涨停家数)，须含 date 当天；mainTheme：调用方挑选出的题材行
     * （aggregate 内部再按主线行业名精确匹配，匹配不上只作展示兜底，不影响 metrics）。
     * manualMainlineIndustry：人工主线标记行业（null=无人工标记）。
     * industryTheme：行业名→用户题材行，用于给雷达区每行附题材关联（可空 map）。
     */
    Snapshot aggregate(LocalDate date,
                       List<MarketStock> todayZT, List<MarketStock> todayZB,
                       List<MarketStock> prevZT, List<MarketStock> prevZB,
                       Map<LocalDate, Map<String, Integer>> dailyIndustryZt,
                       Long userId, Theme mainTheme, String manualMainlineIndustry,
                       Map<String, Theme> industryTheme) {
        Snapshot s = new Snapshot();
        s.ztTotal = todayZT.size();
        s.zbTotal = todayZB.size();

        // ---------- 雷达近端 + 评分对象选择（人工标记 > 自动主线≥3天 > 当日候选榜首） ----------
        Map<String, Integer> industryZt = new LinkedHashMap<String, Integer>();
        List<RadarRow> radar = new ArrayList<RadarRow>();
        for (MarketStock row : todayZT) {
            String ind = row.getIndustry();
            if (ind == null || ind.trim().isEmpty()) {
                continue;
            }
            Integer n = industryZt.get(ind);
            industryZt.put(ind, n == null ? 1 : n + 1);
        }
        for (Map.Entry<String, Integer> e : industryZt.entrySet()) {
            RadarRow r = new RadarRow();
            r.industry = e.getKey();
            Theme th = industryTheme == null ? null : industryTheme.get(e.getKey());
            r.theme = th == null || th.getName() == null ? null : th.getName().trim();
            r.zt = e.getValue();
            int mb = 0;
            MarketStock lead = null;
            for (MarketStock row : todayZT) {
                if (!e.getKey().equals(row.getIndustry())) {
                    continue;
                }
                int nb = row.getConsecutive() == null ? 1 : row.getConsecutive();
                if (nb > mb) {
                    mb = nb;
                    lead = row;
                } else if (nb == mb && lead != null) {
                    BigDecimal ca = row.getChangePct();
                    BigDecimal cb = lead.getChangePct();
                    if (ca != null && (cb == null || ca.compareTo(cb) > 0
                            || (ca.compareTo(cb) == 0 && row.getCode() != null
                                && row.getCode().compareTo(lead.getCode() == null ? "" : lead.getCode()) < 0))) {
                        lead = row;
                    }
                }
            }
            r.maxBoard = mb;
            r.leader = lead;
            r.persistenceDays = consecutiveTop5Days(dailyIndustryZt, date, e.getKey());
            r.isMainline = r.persistenceDays >= MAINLINE_CONFIRM_DAYS;
            r.flag = r.isMainline ? "MAIN" : (r.persistenceDays == 2 ? "WATCH" : "NEW");
            r.ztGatherPct = s.ztTotal == 0 ? 0 : round2(r.zt * 100.0 / s.ztTotal);
            radar.add(r);
        }
        // 排序：zt 降序 → 持续天数降序 → 行业名升序（确定性）
        radar.sort(new Comparator<RadarRow>() {
            @Override
            public int compare(RadarRow a, RadarRow b) {
                int c = Integer.compare(b.zt, a.zt);
                if (c != 0) return c;
                int d = Integer.compare(b.persistenceDays, a.persistenceDays);
                if (d != 0) return d;
                return a.industry.compareTo(b.industry);
            }
        });
        s.radar = radar;
        s.radarTopIndustry = radar.isEmpty() ? null : radar.get(0).industry;

        // ---------- 题材表：把板块表按题材归并（共享同一 t_theme 的行业合并成一行）----------
        // 只收"今日有涨停且已登记题材"的行业；未登记题材不进题材表（板块表已全量覆盖）。
        // 合并规则：涨停家数相加、最高板取最大、持续天数取成员里最久的（题材持续=最强成员行业），龙头取该成员。
        Map<String, RadarRow> themeMap = new LinkedHashMap<String, RadarRow>();
        for (RadarRow r : radar) {
            if (r.theme == null || r.theme.isEmpty()) {
                continue;
            }
            RadarRow g = themeMap.get(r.theme);
            if (g == null) {
                RadarRow n = new RadarRow();
                n.industry = r.theme;          // 题材表主键=题材名（1:1 时即行业名，升级动作线可直接用）
                n.theme = r.theme;
                n.zt = r.zt;
                n.maxBoard = r.maxBoard;
                n.leader = r.leader;
                n.persistenceDays = r.persistenceDays;
                n.ztGatherPct = r.ztGatherPct;
                themeMap.put(r.theme, n);
            } else {
                g.zt += r.zt;
                if (r.maxBoard > g.maxBoard) {
                    g.maxBoard = r.maxBoard;
                }
                if (r.persistenceDays > g.persistenceDays) {
                    g.persistenceDays = r.persistenceDays;
                    g.leader = r.leader;       // 持续更久的成员，其龙头更能代表题材
                }
            }
        }
        List<RadarRow> radarThemes = new ArrayList<RadarRow>(themeMap.values());
        for (RadarRow r : radarThemes) {
            if (s.ztTotal > 0) {
                r.ztGatherPct = round2(r.zt * 100.0 / s.ztTotal);
            }
            r.isMainline = r.persistenceDays >= MAINLINE_CONFIRM_DAYS;
            r.flag = r.isMainline ? "MAIN" : (r.persistenceDays == 2 ? "WATCH" : "NEW");
        }
        radarThemes.sort(new Comparator<RadarRow>() {
            @Override
            public int compare(RadarRow a, RadarRow b) {
                int c = Integer.compare(b.zt, a.zt);
                if (c != 0) return c;
                int d = Integer.compare(b.persistenceDays, a.persistenceDays);
                if (d != 0) return d;
                return a.industry.compareTo(b.industry);
            }
        });
        s.radarThemes = radarThemes;

        // 自动主线：radar 中 ≥3天 者，取最强者（今日涨停最多；平局按天数多）
        RadarRow auto = null;
        for (RadarRow r : radar) {
            if (!r.isMainline) {
                continue;
            }
            if (auto == null || r.zt > auto.zt || (r.zt == auto.zt && r.persistenceDays > auto.persistenceDays)) {
                auto = r;
            }
        }
        s.autoMainlineIndustry = auto == null ? null : auto.industry;

        // 评分对象选择：人工标记 > 自动主线(≥3天) > 当日候选榜首
        boolean hasManualInToday = manualMainlineIndustry != null && industryZt.containsKey(manualMainlineIndustry);
        String main;
        boolean lineConfirmed;
        if (hasManualInToday) {
            main = manualMainlineIndustry;
            lineConfirmed = true;
            s.manuallyMarked = true;
        } else if (auto != null) {
            main = auto.industry;
            lineConfirmed = true;
            s.manuallyMarked = false;
        } else {
            main = s.radarTopIndustry;
            lineConfirmed = false;
            s.manuallyMarked = false;
        }
        s.mainIndustry = main;
        s.mainlineConfirmed = main != null && lineConfirmed;
        int mainZt = main == null ? 0 : (industryZt.get(main) == null ? 0 : industryZt.get(main));
        s.mainZt = mainZt;
        s.persistenceDays = main == null ? null : consecutiveDays(dailyIndustryZt, date, main);

        // ---------- 高度（总龙头=最高连板；同板高取涨幅最大，同涨幅取代码保证确定性） ----------
        int maxBoard = 0;
        MarketStock zongLong = null;
        for (MarketStock row : todayZT) {
            int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (n < maxBoard) {
                continue;
            }
            if (n > maxBoard || zongLong == null) {
                maxBoard = n;
                zongLong = row;
                continue;
            }
            BigDecimal ca = row.getChangePct();
            BigDecimal cb = zongLong.getChangePct();
            if (ca != null && (cb == null || ca.compareTo(cb) > 0
                    || (ca.compareTo(cb) == 0 && row.getCode() != null
                        && row.getCode().compareTo(zongLong.getCode() == null ? "" : zongLong.getCode()) < 0))) {
                zongLong = row;
            }
        }
        s.maxBoard = maxBoard;
        s.zongLong = zongLong;

        // 主线行业内最高板（板块内龙头）：同板高取涨幅最大、同涨幅取代码最小——与全市场总龙头同一 tie-break，
        // 这样空间板在别的行业时，页面能同时给出"市场总龙头 4 板（他行业）"和"板块内龙头 2 板"两个事实。
        int mainMax = 0;
        MarketStock mainLeader = null;
        if (main != null) {
            for (MarketStock row : todayZT) {
                if (!main.equals(row.getIndustry())) {
                    continue;
                }
                int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
                if (n < mainMax) {
                    continue;
                }
                if (n > mainMax || mainLeader == null) {
                    mainMax = n;
                    mainLeader = row;
                    continue;
                }
                BigDecimal ca = row.getChangePct();
                BigDecimal cb = mainLeader.getChangePct();
                if (ca != null && (cb == null || ca.compareTo(cb) > 0
                        || (ca.compareTo(cb) == 0 && row.getCode() != null
                            && row.getCode().compareTo(mainLeader.getCode() == null ? "" : mainLeader.getCode()) < 0))) {
                    mainLeader = row;
                }
            }
        }
        s.mainMaxBoard = mainMax;
        s.mainLeader = mainLeader;

        // ---------- 五要素 ----------
        if (main != null && s.ztTotal > 0) {
            s.ztGatherPct = pct(mainZt, s.ztTotal);
            s.metrics.put("zt_gather_pct", bd(s.ztGatherPct));
        }
        if (maxBoard > 0 && main != null) {
            s.heightGatherPct = pct(mainMax, maxBoard);
            s.metrics.put("height_gather_pct", bd(s.heightGatherPct));
            // 空间板归属：全市场 H 在不在主线行业。板数比照算（阶梯用），但"不在"由引擎砍半——
            // 元件案例：H=4 在家居用品、主线元件最高 2 板，板数比 50% 给 70 分会掩盖"空间板不在我这"。
            boolean spaceInMain = zongLong != null && main.equals(zongLong.getIndustry());
            s.spaceBoardInMain = spaceInMain;
            s.metrics.put(METRIC_SPACE_IN_MAIN, spaceInMain ? BigDecimal.ONE : BigDecimal.ZERO);
        }
        // 成交额聚集度（涨停股口径，唯一口径，不接受人工覆盖）：
        // 主线涨停股成交额 / 全部涨停股成交额——涨停资金在板块间的聚集度。
        // 刻意不用"板块总成交额/两市成交额"：t_market_stock 只有三池个股，且旧两市人工列语义不同、已停用。
        // 只累加 amount 非空行；整列缺失（迁移前的历史行）不产键=未评，绝不把 null 当 0。
        if (main != null) {
            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal mainAmount = BigDecimal.ZERO;
            for (MarketStock row : todayZT) {
                BigDecimal a = row.getAmount();
                if (a == null) {
                    continue;
                }
                totalAmount = totalAmount.add(a);
                if (main.equals(row.getIndustry())) {
                    mainAmount = mainAmount.add(a);
                }
            }
            if (totalAmount.signum() > 0) {
                double p = round2(mainAmount.doubleValue() * 100.0 / totalAmount.doubleValue());
                s.amountGatherPct = p;
                s.metrics.put("amount_gather_pct", bd(p));
            }
        }
        // 持续性：已在评分对象选择处经 consecutiveDays 算好（同一阈值 HOT_ZT_THRESHOLD），这里只落 metrics 键。
        if (main != null) {
            s.metrics.put("persistence_days", BigDecimal.valueOf(s.persistenceDays == null ? 0 : s.persistenceDays));
        }
        // 催化剂硬度：题材名与主线行业完全一致才认（industry≠题材的已知口径差，宁缺勿错）。
        Theme matched = matchTheme(mainTheme, main, userId, date);
        if (matched != null) {
            s.mainTheme = matched;
            Integer hardness = matched.getCatalystHardness();
            if (hardness == null) {
                hardness = 3; // 迁移前列的默认值与 DDL DEFAULT 3 一致
            }
            s.metrics.put("catalyst_hardness", BigDecimal.valueOf(hardness));
        }

        // ---------- 龙头分工 ----------
        Map<String, Integer> prevZtByCode = new HashMap<String, Integer>();
        for (MarketStock row : prevZT) {
            prevZtByCode.put(row.getCode(), row.getConsecutive() == null ? 1 : row.getConsecutive());
        }
        if (zongLong != null) {
            int n = zongLong.getConsecutive() == null ? 1 : zongLong.getConsecutive();
            Integer prevN = prevZtByCode.get(zongLong.getCode());
            s.zongLongPromoted = prevN != null && prevN == n - 1;
            s.zongLongAction = s.zongLongPromoted ? "PROMOTE" : "HOLD";
            StringBuilder r = new StringBuilder();
            r.append("选取：全市场最高连板 H=").append(n).append(" 板（同板高取涨幅最大）");
            if (s.zongLongPromoted) {
                r.append("；今日晋级（昨日 ").append(n - 1).append(" 板 → 今日 ").append(n).append(" 板）。");
            } else if (prevN != null) {
                r.append("；今日持稳在 ").append(n).append(" 板（昨日同为 ").append(prevN)
                        .append(" 板，非晋级）。");
            } else {
                r.append("；昨日明细中无该股，无法确认晋级路径，按持稳计。");
            }
            s.dragonReason = r.toString();
        } else {
            // 今日无涨停池（或最高板缺失）：看昨日最高板的下场
            MarketStock prevTop = topBoard(prevZT);
            if (prevTop != null) {
                s.zongLong = prevTop; // 供页面展示"谁断的"
                int prevN = prevTop.getConsecutive() == null ? 1 : prevTop.getConsecutive();
                MarketStock todayRow = findByCode(todayZT, prevTop.getCode());
                MarketStock bombRow = findByCode(todayZB, prevTop.getCode());
                StringBuilder r = new StringBuilder();
                r.append("选取：昨日最高连板 ").append(prevN).append(" 板（").append(prevTop.getName()).append("）");
                if (todayRow != null) {
                    s.zongLongAction = "HOLD";
                    r.append("；今日仍在涨停池。");
                } else if (bombRow != null) {
                    // 断板：收跌>5% 或日内大幅回撤 → 重罚；否则 25
                    BigDecimal chg = bombRow.getChangePct();
                    BigDecimal pull = bombRow.getPullbackPct();
                    boolean severe = (chg != null && chg.compareTo(new BigDecimal("-5")) < 0)
                            || (pull != null && pull.compareTo(new BigDecimal("7")) >= 0);
                    s.zongLongAction = "BREAK";
                    r.append("；今日断板落入炸板池");
                    if (chg != null) {
                        r.append("，收涨 ").append(chg).append("%");
                    }
                    if (pull != null) {
                        r.append("，自涨停回撤 ").append(pull).append("%");
                    }
                    r.append(severe ? "（收跌>5% 或回撤≥7%，重罚态）" : "（未触发重罚线）");
                } else {
                    s.zongLongAction = "ABSENT";
                    r.append("；今日既未涨停也未进炸板池（缺席/明细未覆盖）。");
                }
                s.dragonReason = r.toString();
            }
        }
        // 中军：主线内除总龙头外的连板≥2（容量担当候选），均涨幅映射
        if (main != null && s.ztTotal > 0) {
            List<MarketStock> zhongJun = new ArrayList<MarketStock>();
            for (MarketStock row : todayZT) {
                if (!main.equals(row.getIndustry()) || row.equals(zongLong)) {
                    continue;
                }
                int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
                if (n >= 2) {
                    zhongJun.add(row);
                }
            }
            s.zhongJun = zhongJun;
            // 跟风：主线涨停里扣掉总龙/中军
            int genCount = mainZt - (zongLong != null && main.equals(zongLong.getIndustry()) ? 1 : 0) - zhongJun.size();
            if (genCount < 0) {
                genCount = 0;
            }
            s.genFengCount = genCount;
        }
        // 卡位：他题材高标。今日他行业最高板封住=80；昨日他行业高标今日炸=40；两者皆无=未评。
        {
            MarketStock todayOtherTop = null;
            int otherTop = 0;
            for (MarketStock row : todayZT) {
                if (main != null && main.equals(row.getIndustry())) {
                    continue;
                }
                int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
                if (n > otherTop) {
                    otherTop = n;
                    todayOtherTop = row;
                }
            }
            if (todayOtherTop != null) {
                s.kaWei = todayOtherTop;
                s.kaWeiSealed = true;
            } else if (prevZT.size() > 0 && prevZB.size() > 0) {
                MarketStock prevOtherTop = null;
                String prevMain = topIndustry(prevZT);
                int prevOther = 0;
                for (MarketStock row : prevZT) {
                    if (prevMain != null && prevMain.equals(row.getIndustry())) {
                        continue;
                    }
                    int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
                    if (n > prevOther) {
                        prevOther = n;
                        prevOtherTop = row;
                    }
                }
                if (prevOtherTop != null && findByCode(todayZB, prevOtherTop.getCode()) != null) {
                    s.kaWei = prevOtherTop;
                    s.kaWeiSealed = false;
                }
            }
        }
        // 反包：昨炸板池 → 今涨停池（仅登记事实供主线/天梯页展示，D5 阵眼分改由人工 t_anchor 体系出）
        // §3 一只票全页只允许一个角色：反包过滤掉已占 总龙头/中军/卡位 的票，避免与主线区重复。
        if (!prevZB.isEmpty()) {
            Set<String> used = new HashSet<String>();
            if (zongLong != null && zongLong.getCode() != null) {
                used.add(zongLong.getCode());
            }
            for (MarketStock zj : s.zhongJun) {
                if (zj.getCode() != null) {
                    used.add(zj.getCode());
                }
            }
            if (s.kaWei != null && s.kaWei.getCode() != null) {
                used.add(s.kaWei.getCode());
            }
            for (MarketStock bomb : prevZB) {
                if (bomb.getCode() != null && used.contains(bomb.getCode())) {
                    continue;
                }
                MarketStock today = findByCode(todayZT, bomb.getCode());
                if (today != null) {
                    s.fanBao.add(today);
                }
            }
        }

        // ---------- 生命周期天花板 / 龙头错位（D2 结构后处理，引擎读这两个控制量）----------
        // 放在龙头分工之后：stage 要看 zongLongAction（BREAK→退潮），错位要拿"今日总龙头"的行业。
        if (main != null) {
            s.metrics.put(METRIC_MAIN_ACTIVE, BigDecimal.ONE);
            int prevMainZt = countIndustry(prevZT, main);
            String stage = lifecycleStage(mainZt, prevMainZt, s.zongLongAction, s.persistenceDays, maxBoard);
            s.lifecycleStage = stage;
            Integer cap = STAGE_CAP.get(stage);
            if (cap != null) {
                s.metrics.put(METRIC_STAGE_CAP, bd(cap));
            }
            // main!=null 时今日涨停池非空，zongLong 必为今日空间板（无涨停的 BREAK 分支里 main 也是 null）。
            boolean aligned = zongLong != null && main.equals(zongLong.getIndustry());
            s.dragonAligned = aligned;
            if (!aligned) {
                s.metrics.put(METRIC_DRAGON_MISALIGN, BigDecimal.ONE);
            }
        }

        // ---------- 轮动信号 ----------
        s.rotationSignals = detectRotation(s, prevZT, todayZT, prevZB);
        return s;
    }

    /**
     * 生命周期阶段（确定性规则，与主线详情页同源）：退潮优先（总龙头断板 BREAK，或主线涨停今日≤昨日一半），
     * 否则持续性 ≥5 天或全市场 H≥7 → 亢奋；≥3 扩散；≥2 确认；其余萌芽。调用方须保证日内核心存在。
     */
    static String lifecycleStage(int mainZt, int prevMainZt, String zongLongAction,
                                 Integer persistenceDays, int maxBoard) {
        boolean ebb = "BREAK".equals(zongLongAction) || (prevMainZt > 0 && mainZt * 2 <= prevMainZt);
        if (ebb) {
            return "退潮";
        }
        int days = persistenceDays == null ? 0 : persistenceDays;
        if (days >= 5 || maxBoard >= 7) {
            return "亢奋";
        }
        if (days >= 3) {
            return "扩散";
        }
        if (days >= 2) {
            return "确认";
        }
        return "萌芽";
    }

    /** 三类轮动信号（PRD RotationEngine 的可推导子集）：老主线退潮 / 新行业种子 / 高低切。 */
    static List<String> detectRotation(Snapshot s, List<MarketStock> prevZT, List<MarketStock> todayZT,
                                       List<MarketStock> prevZB) {
        List<String> out = new ArrayList<String>();
        String prevMain = topIndustry(prevZT);
        int prevMainCount = prevMain == null ? 0 : countIndustry(prevZT, prevMain);
        // 1. 老主线退潮：昨日主线今天让位且涨停腰斩
        if (prevMain != null && s.mainIndustry != null && !prevMain.equals(s.mainIndustry)) {
            int todayCount = countIndustry(todayZT, prevMain);
            if (todayCount * 2 <= prevMainCount) {
                out.add("老主线退潮：" + prevMain + " 涨停 " + prevMainCount + "→" + todayCount
                        + "，主线让位 " + s.mainIndustry);
            }
        }
        // 2. 新题材种子：昨日 0 涨停、今日 ≥3 家的行业
        if (prevZT.size() > 0) {
            Map<String, Integer> todayByInd = new LinkedHashMap<String, Integer>();
            for (MarketStock row : todayZT) {
                String ind = row.getIndustry();
                if (ind == null || ind.trim().isEmpty()) {
                    continue;
                }
                Integer n = todayByInd.get(ind);
                todayByInd.put(ind, n == null ? 1 : n + 1);
            }
            List<String> seeds = new ArrayList<String>();
            for (Map.Entry<String, Integer> e : todayByInd.entrySet()) {
                if (e.getValue() >= SEED_ZT_THRESHOLD && countIndustry(prevZT, e.getKey()) == 0) {
                    seeds.add(e.getKey() + "(" + e.getValue() + ")");
                }
            }
            if (!seeds.isEmpty()) {
                out.add("新晋行业种子【昨日0家涨停 → 今日≥3家】：" + join(seeds) + "，括号内为今日涨停家数");
            }
        }
        // 3. 高低切：昨日最高板断板 + 今日首板家数放大
        if (!prevZT.isEmpty() && !prevZB.isEmpty() && s.zongLong != null) {
            boolean prevTopBroke = true;
            for (MarketStock row : todayZT) {
                if (row.getCode().equals(s.zongLong.getCode())) {
                    prevTopBroke = false;
                    break;
                }
            }
            int firstToday = 0;
            for (MarketStock row : todayZT) {
                if (row.getConsecutive() == null || row.getConsecutive() <= 1) {
                    firstToday++;
                }
            }
            int firstPrev = 0;
            for (MarketStock row : prevZT) {
                if (row.getConsecutive() == null || row.getConsecutive() <= 1) {
                    firstPrev++;
                }
            }
            if (prevTopBroke && findByCode(prevZB, s.zongLong.getCode()) != null
                    && firstToday > firstPrev && firstToday >= 20) {
                out.add("高低切信号：最高板 " + s.zongLong.getName() + " 断板，首板 " + firstPrev + "→" + firstToday + " 放量");
            }
        }
        return out;
    }

    // ================= helpers =================

    /** 主线题材精确匹配：名称与主线行业一致 + 归属该账号。匹配不上返回 null（硬度=未评，不硬凑）。 */
    private Theme matchTheme(Theme candidate, String main, Long userId, LocalDate date) {
        if (main == null || userId == null) {
            return null;
        }
        try {
            List<Theme> rows = themeMapper.selectList(new LambdaQueryWrapper<Theme>()
                    .eq(Theme::getUserId, userId)
                    .eq(Theme::getName, main)
                    .orderByDesc(Theme::getCreatedAt)
                    .last("LIMIT 1"));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            log.warn("主线题材匹配失败 user={} main={} 原因={}", userId, main, e.toString());
            return null;
        }
    }

    private List<MarketStock> listPool(LocalDate date, String pool) {
        return marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, pool));
    }

    private static MarketStock topBoard(List<MarketStock> rows) {
        MarketStock best = null;
        for (MarketStock row : rows) {
            int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (best == null || n > (best.getConsecutive() == null ? 1 : best.getConsecutive())) {
                best = row;
            }
        }
        return best;
    }

    private static String topIndustry(List<MarketStock> rows) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (MarketStock row : rows) {
            String ind = row.getIndustry();
            if (ind == null || ind.trim().isEmpty()) {
                continue;
            }
            Integer n = counts.get(ind);
            counts.put(ind, n == null ? 1 : n + 1);
        }
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static int countIndustry(List<MarketStock> rows, String industry) {
        int n = 0;
        for (MarketStock row : rows) {
            if (industry.equals(row.getIndustry())) {
                n++;
            }
        }
        return n;
    }

    /**
     * 任意行业的连续持续天数（走 PERSISTENCE_WINDOW），主线持续性与雷达区共用这一个口径，杜绝双标。
     *
     * <p><b>活跃口径（2026-09-13 定稿）</b>：数的是"连续活跃"而不是"过热"。只要该行业当日涨停家数
     * 达到 {@link #SEED_ZT_THRESHOLD}（3 家，区别于"过热日" ≥ {@link #HOT_ZT_THRESHOLD}5），这一天就算
     * 在持续，逐日 +1。例：10 号 5 家=持续第 1 天，11 号 4 家（仍 ≥3 活跃）=持续第 2 天；
     * 12 号若掉到 3 家以下或当天无涨停（真正走弱）才归零。过热与否交给 cluster/对象过热打分，不掺进天数。
     */
    static int consecutiveDays(Map<LocalDate, Map<String, Integer>> dailyIndustryZt,
                               LocalDate date, String industry) {
        if (date == null || industry == null || dailyIndustryZt == null
                || !dailyIndustryZt.containsKey(date)) {
            return 0;
        }
        int days = 0;
        LocalDate cursor = date;
        for (int i = 0; i < PERSISTENCE_WINDOW; i++) {
            Map<String, Integer> byIndustry = dailyIndustryZt.get(cursor);
            Integer n = byIndustry == null ? null : byIndustry.get(industry);
            if (n == null || n < SEED_ZT_THRESHOLD) {
                break;
            }
            days++;
            cursor = cursor.minusDays(1);
        }
        return days;
    }

    /**
     * 板块表「连续」口径：该行业当天涨停家数排进板块表前五、且连续保持，逐日 +1。
     * 与 {@link #consecutiveDays}（≥阈值活跃日）不同——只按「进前五」算一天。
     * 例：09-10 元件 2 家列第 2（前五内）→ 第 1 天；09-11 元件 9 家居首 → 第 2 天。
     * <p>前五判定严格复现板块表显示顺序（涨停家数降序 → 连续天数降序 → 名称升序，取前 5 行），
     * 并列涨停家数不全会算进前五（会被连板更高的行业挤出）。「连续天数」既是结果又参与排序——
     * 故用定点迭代求解：以涨停家数为第一 key，次 key 用上一轮连续数，迭代至连续数收敛。
     */
    static int consecutiveTop5Days(Map<LocalDate, Map<String, Integer>> dailyIndustryZt,
                                   LocalDate date, String industry) {
        if (date == null || industry == null || dailyIndustryZt == null
                || !dailyIndustryZt.containsKey(date)) {
            return 0;
        }
        // 收集窗口内实际有涨停数据的日期（升序；缺日即断档，计入 break）
        List<LocalDate> days = new ArrayList<LocalDate>();
        LocalDate cursor = date;
        for (int i = 0; i < PERSISTENCE_WINDOW; i++) {
            if (!dailyIndustryZt.containsKey(cursor)) {
                break;
            }
            days.add(cursor);
            cursor = cursor.minusDays(1);
        }
        Collections.reverse(days);
        // key = "date|industry"，value = 截至该日的连续前五天数
        Map<String, Integer> streak = new HashMap<String, Integer>();
        for (int iter = 0; iter < PERSISTENCE_WINDOW * 2 + 1; iter++) {
            Map<String, Integer> next = new HashMap<String, Integer>();
            Map<LocalDate, Set<String>> top5 = new HashMap<LocalDate, Set<String>>();
            final Map<String, Integer> baseStreak = streak;
            for (LocalDate d : days) {
                Map<String, Integer> by = dailyIndustryZt.get(d);
                List<String> inds = new ArrayList<String>(by.keySet());
                Collections.sort(inds, (x, y) -> {
                    int c = Integer.compare(by.get(y), by.get(x));
                    if (c != 0) {
                        return c;
                    }
                    String kx = d + "|" + x;
                    String ky = d + "|" + y;
                    int s = Integer.compare(streakD(baseStreak, ky), streakD(baseStreak, kx));
                    if (s != 0) {
                        return s;
                    }
                    return x.compareTo(y);
                });
                Set<String> set = new HashSet<String>();
                for (int k = 0; k < inds.size() && k < 5; k++) {
                    set.add(inds.get(k));
                }
                top5.put(d, set);
            }
            for (LocalDate d : days) {
                for (String ind : dailyIndustryZt.get(d).keySet()) {
                    int v = 0;
                    LocalDate c = d;
                    while (top5.containsKey(c) && top5.get(c).contains(ind)) {
                        v++;
                        c = c.minusDays(1);
                    }
                    next.put(d + "|" + ind, v);
                }
            }
            if (next.equals(streak)) {
                break;
            }
            streak = next;
        }
        return streak.getOrDefault(date + "|" + industry, 0);
    }

    private static int streakD(Map<String, Integer> streak, String key) {
        Integer v = streak.get(key);
        return v == null ? 0 : v;
    }

    private static MarketStock findByCode(List<MarketStock> rows, String code) {
        for (MarketStock row : rows) {
            if (row.getCode() != null && row.getCode().equals(code)) {
                return row;
            }
        }
        return null;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    static Double pct(int part, int total) {
        return total == 0 ? null : round2(part * 100.0 / total);
    }

    static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
