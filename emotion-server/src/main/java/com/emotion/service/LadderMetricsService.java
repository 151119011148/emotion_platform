package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.IndexClose;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.MarketMetrics;
import com.emotion.market.PoolCounts;

/**
 * 五维模型的「自动取数聚合器」：把 {@code t_market_stock}(日频个股连板)、{@code t_premium_tier}(逐档溢价)、
 * {@code t_index_close}(指数) 与 {@code t_daily_record} 聚合出一张 {@code metrics}（键=各子指标 source_key），
 * 交给 {@link com.emotion.util.BoardScoreCalculator} 打分。
 *
 * <p>与引擎同一哲学：<b>取不到的键直接不出现在 map 里（=未评），绝不写 0</b>——0 是真实读数会被当成"今天确实没大面"。
 * 缺读数时引擎按已评权重归一化，人工列（Stage 5 在此之上叠加）补齐取不到的定性/首板溢价项。
 *
 * <p>四层（低/中/中高/极高）按<b>当日最高板 H 动态划界</b>（见 {@link #layerOf}），跨日按 code 匹配算晋级/大面：
 * 晋级率用"今 b 板家数 / 昨 b-1 板家数"，大面用"昨 b 板且今日炸成大面的家数"。上游未落 ZB 的连板数，故大面只能
 * 靠昨日涨停池归属，非涨停来源的大面不计入（连板生态口径下的近似，已在 R3 里记为已知取数边界）。
 */
@Service
public class LadderMetricsService {

    /** 三指（上证/深成/创业板）按此顺序映射 index1/2/3_pct；缺任一则指数环境子整支未评（引擎只认三指齐全）。 */
    static final String[] INDEX_CODES = {"000001", "399001", "399006"};
    /** 量能基准窗口：成交额对最近这么多个交易日的均值求比。 */
    static final int TURNOVER_WINDOW = 20;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final MarketStockMapper marketStockMapper;
    private final PremiumTierStore premiumTierStore;
    private final IndexCloseStore indexCloseStore;

    public LadderMetricsService(MarketStockMapper marketStockMapper,
                                PremiumTierStore premiumTierStore,
                                IndexCloseStore indexCloseStore) {
        this.marketStockMapper = marketStockMapper;
        this.premiumTierStore = premiumTierStore;
        this.indexCloseStore = indexCloseStore;
    }

    /** 从库里读当天+前一交易日明细，产出 metrics；record/recent 由调用方（ScoreContextService/recalc）传入。 */
    public Map<String, BigDecimal> build(LocalDate date, DailyRecord record, List<DailyRecord> recent) {
        List<MarketStock> todayZT = listPool(date, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> todayLoss = listBigLoss(date);
        List<MarketStock> prevZT = Collections.emptyList();
        LocalDate prev = safePrevDetailDate(date);
        if (prev != null) {
            prevZT = listPool(prev, MarketStock.POOL_LIMIT_UP);
        }
        PoolCounts pools = safeCountPools(date);
        List<MarketMetrics.TierPremium> tiers = Collections.emptyList();
        MarketMetrics.PremiumTiers pt = premiumTierStore.read(date);
        if (pt != null && pt.getTiers() != null) {
            tiers = pt.getTiers();
        }
        List<IndexClose> idx = indexCloseStore.read(date);
        return aggregate(todayZT, todayLoss, prevZT, pools, tiers, idx, record, recent);
    }

    /**
     * 纯聚合（不碰库），供单测直接喂内存 fixture 池数据。任何一路缺数据只是让对应键缺席。
     *
     * @param todayZT   当日涨停池明细（consecutive=今连板数）
     * @param todayLoss 当日大面明细（ZB 池且 big_loss=1）
     * @param prevZT    前一交易日涨停池明细（consecutive=昨连板数），用于跨日匹配
     * @param pools     当日三池家数（封板率/回封率用）
     * @param tiers     当日逐档溢价（board=昨连板数）
     * @param idx       当日指数收盘
     * @param record    当日记录（涨跌家数、量能、涨跌停、最高板兜底）
     * @param recent    该日之前若干交易日记录（升序），量能 20 日均用
     */
    static Map<String, BigDecimal> aggregate(List<MarketStock> todayZT, List<MarketStock> todayLoss,
                                             List<MarketStock> prevZT, PoolCounts pools,
                                             List<MarketMetrics.TierPremium> tiers, List<IndexClose> idx,
                                             DailyRecord record, List<DailyRecord> recent) {
        Map<String, BigDecimal> out = new TreeMap<>();

        Map<Integer, Integer> todayByBoard = countByBoard(todayZT);
        Map<Integer, Integer> prevByBoard = countByBoard(prevZT);
        Integer h = maxHeight(todayByBoard, record);
        // max_height is consumed by forced-ebb cond 4 independently of the layer partition.
        if (h != null) {
            out.put("max_height", BigDecimal.valueOf(h));
        }

        // Counters from today's ZT pool: absent (unscored) when the fetcher returned nothing,
        // never a fake 0 -- 0 would read as "we looked and there were truly no boards today".
        if (!todayZT.isEmpty()) {
            putScalar(out, "board_total_count", sumBoards(todayByBoard, 2));
            putScalar(out, "first_count", todayByBoard.getOrDefault(1, 0));
        }

        Map<String, Integer> prevCodeBoard = codeToBoard(prevZT);
        Set<String> lossCodes = lossCodes(todayLoss);
        // 1->2 promo rate needs yesterday's first-board denominator; putRate skips when missing.
        putRate(out, "first_promo_1to2_rate", todayByBoard.get(2), prevByBoard.get(1));
        // 1->2 big-loss count needs yesterday's pool to attribute against; skip rather than emit false 0.
        if (!prevZT.isEmpty()) {
            int firstToTwoBig = 0;
            for (Map.Entry<String, Integer> e : prevCodeBoard.entrySet()) {
                if (e.getValue() != null && e.getValue() == 1 && lossCodes.contains(e.getKey())) {
                    firstToTwoBig++;
                }
            }
            out.put("first_1to2_big_count", BigDecimal.valueOf(firstToTwoBig));
        }

        // 四层：仅在能定界(有 H)时产出；各层家数/溢价/大面缺样本则该键缺席
        if (h != null && h >= 2) {
            computeLayers(out, h, todayByBoard, prevByBoard, prevCodeBoard, lossCodes, tiers);
        }

        // 炸板质量（家数封板率 / 回封率）用三池家数现算
        if (pools != null && !pools.isEmpty()) {
            int zt = pools.getZtCount();
            int zb = pools.getZbCount();
            if (zt + zb > 0) {
                out.put("sealed_home_rate", pct(zt, zt + zb));
            }
            if (zb > 0) {
                out.put("reseal_rate", pct(pools.getResealCount(), zb));
            }
        }

        // 大盘：涨跌停家数、红盘率、量能比、指数
        if (record != null) {
            putScalar(out, "limit_up_count", record.getLimitUpCount());
            putScalar(out, "limit_down_count", record.getLimitDownCount());
            putRedRatio(out, record);
            putTurnoverRatio(out, record, recent);
        }
        putIndexPct(out, idx);
        return out;
    }

    private static void computeLayers(Map<String, BigDecimal> out, int h,
                                      Map<Integer, Integer> todayByBoard, Map<Integer, Integer> prevByBoard,
                                      Map<String, Integer> prevCodeBoard, Set<String> lossCodes,
                                      List<MarketMetrics.TierPremium> tiers) {
        String[] keyPrefix = {"jr", "prem", "big"};
        String[] layerSuffix = {"low", "mid", "midhigh", "top"};
        for (String p : keyPrefix) {
            int[] num = new int[4];
            int[] den = new int[4];
            int[] count = new int[4];
            switch (p) {
                case "jr":
                    for (int b = 2; b <= h; b++) {
                        int li = layerIndex(b, h);
                        num[li] += nz(todayByBoard.get(b));
                        den[li] += nz(prevByBoard.get(b - 1));
                    }
                    for (int li = 0; li < 4; li++) {
                        putRate(out, keyPrefix[0] + "_" + layerSuffix[li], num[li], den[li]);
                    }
                    break;
                case "big":
                    if (prevCodeBoard.isEmpty()) {
                        break; // no yesterday pool: cannot attribute big-loss to layers; keys stay absent
                    }
                    for (Map.Entry<String, Integer> e : prevCodeBoard.entrySet()) {
                        Integer b = e.getValue();
                        if (b != null && b >= 2 && lossCodes.contains(e.getKey())) {
                            count[layerIndex(b, h)]++;
                        }
                    }
                    for (int li = 0; li < 4; li++) {
                        out.put("big_" + layerSuffix[li], BigDecimal.valueOf(count[li]));
                    }
                    break;
                case "prem":
                    for (MarketMetrics.TierPremium t : tiers) {
                        if (t.getBoard() < 2 || t.getAvgPct() == null) {
                            continue;
                        }
                        int li = layerIndex(t.getBoard(), h);
                        // 用档位家数做权重，聚合到该层再加权平均
                        BigDecimal w = BigDecimal.valueOf(Math.max(1, t.getStockCount()));
                        out.merge("prem_" + layerSuffix[li] + "__num", t.getAvgPct().multiply(w), BigDecimal::add);
                        out.merge("prem_" + layerSuffix[li] + "__den", w, BigDecimal::add);
                    }
                    for (int li = 0; li < 4; li++) {
                        BigDecimal n = out.remove("prem_" + layerSuffix[li] + "__num");
                        BigDecimal d = out.remove("prem_" + layerSuffix[li] + "__den");
                        if (n != null && d != null && d.signum() > 0) {
                            out.put("prem_" + layerSuffix[li], n.divide(d, 2, RoundingMode.HALF_UP));
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    }

    // ---------------- 四层划界（当日最高板 H 动态）：低=2；中=3-4；中高=5..hsplit；极高=hsplit+1..H ----------------

    /** {@code hsplit = max(4, ⌈H/2⌉)}；H<9 时中高段自然为空 → 退化成三层（低/中/高位），对齐 spec 的边界说明。 */
    static int hsplit(int h) {
        return Math.max(4, (h + 1) / 2);
    }

    /** 返回 0=低/1=中/2=中高/3=极高。 */
    static int layerIndex(int board, int h) {
        if (board <= 2) {
            return 0;
        }
        if (board <= 4) {
            return 1;
        }
        return board <= hsplit(h) ? 2 : 3;
    }

    // ---------------- 小工具 ----------------

    private static Map<Integer, Integer> countByBoard(List<MarketStock> zt) {
        Map<Integer, Integer> m = new HashMap<>();
        for (MarketStock s : zt) {
            Integer c = s.getConsecutive();
            if (c != null && c >= 1) {
                m.merge(c, 1, Integer::sum);
            }
        }
        return m;
    }

    private static Map<String, Integer> codeToBoard(List<MarketStock> zt) {
        Map<String, Integer> m = new HashMap<>();
        for (MarketStock s : zt) {
            if (s.getCode() != null) {
                m.put(s.getCode(), s.getConsecutive());
            }
        }
        return m;
    }

    private static Set<String> lossCodes(List<MarketStock> loss) {
        Set<String> s = new HashSet<>();
        for (MarketStock m : loss) {
            if (m.getCode() != null) {
                s.add(m.getCode());
            }
        }
        return s;
    }

    private static Integer maxHeight(Map<Integer, Integer> todayByBoard, DailyRecord record) {
        int best = 0;
        for (Integer b : todayByBoard.keySet()) {
            if (b != null && b > best) {
                best = b;
            }
        }
        if (best >= 2) {
            return best;
        }
        Integer rec = record == null ? null : record.getMaxConsecutiveLimit();
        return rec != null && rec >= 2 ? rec : (best >= 1 ? best : null);
    }

    private static Integer sumBoards(Map<Integer, Integer> byBoard, int fromInclusive) {
        int sum = 0;
        for (Map.Entry<Integer, Integer> e : byBoard.entrySet()) {
            if (e.getKey() != null && e.getKey() >= fromInclusive) {
                sum += nz(e.getValue());
            }
        }
        return sum;
    }

    private static void putScalar(Map<String, BigDecimal> out, String key, Integer value) {
        if (value != null) {
            out.put(key, BigDecimal.valueOf(value));
        }
    }

    /** numerator/denominator×100 两位；分母缺或 0 → 缺席（未评）。 */
    private static void putRate(Map<String, BigDecimal> out, String key, Integer numerator, Integer denominator) {
        if (numerator == null || denominator == null || denominator <= 0) {
            return;
        }
        out.put(key, pct(numerator, denominator));
    }

    private static BigDecimal pct(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private static void putRedRatio(Map<String, BigDecimal> out, DailyRecord record) {
        Integer up = record.getUpCount();
        Integer down = record.getDownCount();
        if (up == null || down == null || up + down <= 0) {
            return;
        }
        out.put("red_ratio", BigDecimal.valueOf(up)
                .divide(BigDecimal.valueOf(up + down), 4, RoundingMode.HALF_UP));
    }

    private static void putTurnoverRatio(Map<String, BigDecimal> out, DailyRecord record, List<DailyRecord> recent) {
        BigDecimal today = record.getTotalVolume();
        if (today == null || recent == null || recent.isEmpty()) {
            return;
        }
        List<BigDecimal> window = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0 && window.size() < TURNOVER_WINDOW; i--) {
            BigDecimal v = recent.get(i) == null ? null : recent.get(i).getTotalVolume();
            if (v != null) {
                window.add(v);
            }
        }
        if (window.isEmpty()) {
            return;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : window) {
            sum = sum.add(v);
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(window.size()), 6, RoundingMode.HALF_UP);
        if (avg.signum() > 0) {
            out.put("turnover_ratio", today.divide(avg, 4, RoundingMode.HALF_UP));
        }
    }

    private static void putIndexPct(Map<String, BigDecimal> out, List<IndexClose> idx) {
        if (idx == null || idx.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> byCode = new HashMap<>();
        for (IndexClose c : idx) {
            if (c.getIndexCode() != null && c.getChangePct() != null) {
                byCode.putIfAbsent(c.getIndexCode(), c.getChangePct());
            }
        }
        for (int i = 0; i < INDEX_CODES.length; i++) {
            BigDecimal v = byCode.get(INDEX_CODES[i]);
            if (v != null) {
                out.put("index" + (i + 1) + "_pct", v);
            }
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // ---------------- 读库封装（异常一律降级成"没有这路数据"，不抛） ----------------

    private List<MarketStock> listPool(LocalDate date, String pool) {
        if (date == null) {
            return Collections.emptyList();
        }
        try {
            return marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                    .eq(MarketStock::getTradeDate, date)
                    .eq(MarketStock::getPool, pool));
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private List<MarketStock> listBigLoss(LocalDate date) {
        if (date == null) {
            return Collections.emptyList();
        }
        try {
            return marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                    .eq(MarketStock::getTradeDate, date)
                    .eq(MarketStock::getPool, MarketStock.POOL_BROKEN)
                    .eq(MarketStock::getBigLoss, 1));
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private LocalDate safePrevDetailDate(LocalDate date) {
        try {
            return marketStockMapper.prevDetailDate(date);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private PoolCounts safeCountPools(LocalDate date) {
        try {
            PoolCounts pc = marketStockMapper.countPools(date);
            return pc == null || pc.isEmpty() ? null : pc;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
