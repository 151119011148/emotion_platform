package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.entity.Theme;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.ThemeMapper;

/**
 * PRD 2.0（five_dim_v2）新增维度的「自动取数聚合器」：
 * 从 {@code t_market_stock}(涨停/炸板池) 与 {@code t_theme} 推导
 * 主线 5 要素（zt_gather_pct / height_gather_pct / catalyst_hardness / persistence_days）与
 * 阵眼龙头分工 5 分（dragon_zong_long / zhong_jun / gen_feng / ka_wei / fan_bao）。
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

    /** 主线"活跃一天"的门槛：当日该行业涨停 ≥3 家。持续性=按此口径往回数连续活跃天数。 */
    static final int ACTIVE_ZT_THRESHOLD = 3;
    /** 持续性回看的最大窗口（交易日，按 t_market_stock 已落库日期计）。 */
    static final int PERSISTENCE_WINDOW = 30;

    private final MarketStockMapper marketStockMapper;
    private final ThemeMapper themeMapper;

    public PrdMetricsService(MarketStockMapper marketStockMapper, ThemeMapper themeMapper) {
        this.marketStockMapper = marketStockMapper;
        this.themeMapper = themeMapper;
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
        public Integer persistenceDays;        // 连续活跃天数（当日不活跃=0）
        public Theme mainTheme;                // 名称与主线行业一致的题材行（可 null）
        public MarketStock zongLong;           // 总龙头=全市场最高连板（可 null）
        public boolean zongLongPromoted;       // 总龙头是否晋级（昨 H-1 今 H）
        public String zongLongAction;          // PROMOTE/HOLD/BREAK/ABSENT
        public List<MarketStock> zhongJun = new ArrayList<>();  // 主线内其余连板≥2（中军候选）
        public int genFengCount;               // 主线内跟风涨停家数（扣掉总龙/中军）
        public MarketStock kaWei;              // 他题材最高标（封住）/昨日他题材高标今炸
        public boolean kaWeiSealed;
        public List<MarketStock> fanBao = new ArrayList<>();    // 昨炸板今回封
        public Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        public List<String> rotationSignals = new ArrayList<>();
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
        try {
            List<Theme> themes = themeMapper.selectList(new LambdaQueryWrapper<Theme>()
                    .eq(userId != null, Theme::getUserId, userId)
                    .orderByDesc(Theme::getCreatedAt));
            for (Theme t : themes) {
                if (t.getName() != null && !t.getName().trim().isEmpty()) {
                    if (mainTheme == null || t.getCreatedAt() == null || mainTheme.getCreatedAt() == null
                            || t.getCreatedAt().isAfter(mainTheme.getCreatedAt())) {
                        mainTheme = t; // 先记最新一行，aggregate 里按行业名匹配后使用
                    }
                }
            }
            // 上面拿的是"最新题材"，但主线可能对不上它；精确匹配交给 aggregate（需要主线名）。
            // 为保持 aggregate 纯函数，这里把"同名行"挑出来，找不到再退最新行供主线页展示。
        } catch (RuntimeException e) {
            log.warn("题材行读取失败 user={} date={} 原因={}（催化剂硬度未评）", userId, date, e.toString());
        }

        return aggregate(date, todayZT, todayZB, prevZT, prevZB, dailyIndustryZt, userId, mainTheme);
    }

    /**
     * 纯聚合（不碰 DB）。dailyIndustryZt：日期→(行业→涨停家数)，须含 date 当天；mainTheme：调用方挑选出的题材行
     * （aggregate 内部再按主线行业名精确匹配，匹配不上只作展示兜底，不影响 metrics）。
     */
    Snapshot aggregate(LocalDate date,
                       List<MarketStock> todayZT, List<MarketStock> todayZB,
                       List<MarketStock> prevZT, List<MarketStock> prevZB,
                       Map<LocalDate, Map<String, Integer>> dailyIndustryZt,
                       Long userId, Theme mainTheme) {
        Snapshot s = new Snapshot();
        s.ztTotal = todayZT.size();
        s.zbTotal = todayZB.size();

        // ---------- 主线行业（涨停聚集度最高） ----------
        Map<String, Integer> industryZt = new LinkedHashMap<String, Integer>();
        for (MarketStock row : todayZT) {
            String ind = row.getIndustry();
            if (ind == null || ind.trim().isEmpty()) {
                continue;
            }
            Integer n = industryZt.get(ind);
            industryZt.put(ind, n == null ? 1 : n + 1);
        }
        String main = null;
        int mainZt = 0;
        for (Map.Entry<String, Integer> e : industryZt.entrySet()) {
            if (e.getValue() > mainZt) {
                mainZt = e.getValue();
                main = e.getKey();
            }
        }
        s.mainIndustry = main;
        s.mainZt = mainZt;

        // ---------- 高度 ----------
        int maxBoard = 0;
        MarketStock zongLong = null;
        for (MarketStock row : todayZT) {
            int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (n > maxBoard) {
                maxBoard = n;
                zongLong = row;
            }
        }
        s.maxBoard = maxBoard;
        s.zongLong = zongLong;

        int mainMax = 0;
        if (main != null) {
            for (MarketStock row : todayZT) {
                if (main.equals(row.getIndustry())) {
                    int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
                    if (n > mainMax) {
                        mainMax = n;
                    }
                }
            }
        }
        s.mainMaxBoard = mainMax;

        // ---------- 五要素 ----------
        if (main != null && s.ztTotal > 0) {
            s.ztGatherPct = pct(mainZt, s.ztTotal);
            s.metrics.put("zt_gather_pct", bd(s.ztGatherPct));
        }
        if (maxBoard > 0 && main != null) {
            s.heightGatherPct = pct(mainMax, maxBoard);
            s.metrics.put("height_gather_pct", bd(s.heightGatherPct));
        }
        // 持续性：主线行业从 date 往回数连续"活跃日"（当日 ZT≥3）。
        if (main != null) {
            int days = 0;
            LocalDate cursor = date;
            for (int i = 0; i < PERSISTENCE_WINDOW; i++) {
                Map<String, Integer> byIndustry = dailyIndustryZt.get(cursor);
                Integer n = byIndustry == null ? null : byIndustry.get(main);
                if (n == null || n < ACTIVE_ZT_THRESHOLD) {
                    break;
                }
                days++;
                cursor = cursor.minusDays(1);
            }
            // 当日活跃但窗口内一个活跃日都没有的边界由 days=0 覆盖；cursor 走出窗口自然停。
            s.persistenceDays = days;
            s.metrics.put("persistence_days", BigDecimal.valueOf(days));
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
            double score = s.zongLongPromoted ? Math.min(100, 60 + n * 6.0) : 55;
            s.metrics.put("dragon_zong_long", bd(score));
        } else {
            // 今日无涨停池（或最高板缺失）：看昨日最高板的下场
            MarketStock prevTop = topBoard(prevZT);
            if (prevTop != null) {
                s.zongLong = prevTop; // 供页面展示"谁断的"
                MarketStock todayRow = findByCode(todayZT, prevTop.getCode());
                MarketStock bombRow = findByCode(todayZB, prevTop.getCode());
                if (todayRow != null) {
                    s.zongLongAction = "HOLD";
                    s.metrics.put("dragon_zong_long", bd(55));
                } else if (bombRow != null) {
                    // 断板：收跌>5% 或日内大幅回撤 → 重罚；否则 25
                    BigDecimal chg = bombRow.getChangePct();
                    BigDecimal pull = bombRow.getPullbackPct();
                    if (chg != null && chg.compareTo(new BigDecimal("-5")) < 0
                            || pull != null && pull.compareTo(new BigDecimal("7")) >= 0) {
                        s.zongLongAction = "BREAK";
                        s.metrics.put("dragon_zong_long", bd(10));
                    } else {
                        s.zongLongAction = "BREAK";
                        s.metrics.put("dragon_zong_long", bd(25));
                    }
                } else {
                    s.zongLongAction = "ABSENT";
                    s.metrics.put("dragon_zong_long", bd(30));
                }
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
            if (!zhongJun.isEmpty()) {
                double sum = 0;
                for (MarketStock row : zhongJun) {
                    sum += row.getChangePct() == null ? 0 : row.getChangePct().doubleValue();
                }
                double avg = sum / zhongJun.size();
                s.metrics.put("dragon_zhong_jun", bd(clamp(50 + avg * 5, 0, 100)));
            }
            // 跟风：主线涨停里扣掉总龙/中军
            int genCount = mainZt - (zongLong != null && main.equals(zongLong.getIndustry()) ? 1 : 0) - zhongJun.size();
            if (genCount < 0) {
                genCount = 0;
            }
            s.genFengCount = genCount;
            s.metrics.put("dragon_gen_feng", bd(Math.min(100, genCount * 20.0)));
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
                s.metrics.put("dragon_ka_wei", bd(80));
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
                    s.metrics.put("dragon_ka_wei", bd(40));
                }
            }
        }
        // 反包：昨炸板池 → 今涨停池
        if (!prevZB.isEmpty()) {
            for (MarketStock bomb : prevZB) {
                MarketStock today = findByCode(todayZT, bomb.getCode());
                if (today != null) {
                    s.fanBao.add(today);
                }
            }
            s.metrics.put("dragon_fan_bao", bd(Math.min(100, s.fanBao.size() * 40.0)));
        }

        // ---------- 轮动信号 ----------
        s.rotationSignals = detectRotation(s, prevZT, todayZT, prevZB);
        return s;
    }

    /** 三类轮动信号（PRD RotationEngine 的可推导子集）：老主线退潮 / 新题材种子 / 高低切。 */
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
                if (e.getValue() >= ACTIVE_ZT_THRESHOLD && countIndustry(prevZT, e.getKey()) == 0) {
                    seeds.add(e.getKey() + "(" + e.getValue() + ")");
                }
            }
            if (!seeds.isEmpty()) {
                out.add("新题材种子：" + join(seeds));
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
