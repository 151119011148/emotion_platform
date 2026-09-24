package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.emotion.entity.CandidateStock;
import com.emotion.entity.CandidateT1;
import com.emotion.mapper.CandidateStockMapper;
import com.emotion.mapper.CandidateT1Mapper;
import com.emotion.waverider.WaveRiderConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复盘指标：候选池在 D+1 的表现，以及权重回归要用的分组统计。
 *
 * <p><strong>所有收益同时给两个口径</strong>，主口径是 A：
 * <ul>
 *   <li><b>A 口径（主）</b> {@code close(D+1)/close(D) − 1}：起算价是 D 日收盘价，
 *       也就是 D 日的涨停价。本策略是<strong>打板</strong>策略，买点本来就在 D 日的
 *       涨停板上，所以这才是它真实的收益口径——前提是当天排到了队。</li>
 *   <li><b>B 口径（对照）</b> {@code close(D+1)/open(D+1) − 1}：以 D+1 开盘价起算。
 *       对应「D 日没打上板、改成 D+1 开盘再接」的备选打法。</li>
 * </ul>
 * 两者实测差得极远（同一批 226 个样本：A +3.36% / B −0.20%），差全在隔夜跳空里。
 *
 * <p><strong>两个口径必须并排看，单看任何一个都会得到系统性偏差</strong>：只摆 A 会乐观
 * （它假设每个板都排到了队），只摆 B 会悲观（它假设你每次都放弃打板、改在次日开盘追高）。
 * 两个数之间的距离，就是「排到队」这件事值多少钱。
 *
 * <p>{@code gapBuckets} 单列隔夜跳空分布：它是「D+1 还追不追」的画像，
 * 也是 B 口径亏损的主要来源。{@code bySealBucket} 按封单强度分档，
 * 是「封单越强、A 口径越赚」在页面上的直接证据。
 */
@Service
public class WaveRiderReviewService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CandidateStockMapper candidateMapper;
    private final CandidateT1Mapper t1Mapper;
    private final WaveRiderConfigService configService;

    public WaveRiderReviewService(CandidateStockMapper candidateMapper,
                                  CandidateT1Mapper t1Mapper,
                                  WaveRiderConfigService configService) {
        this.candidateMapper = candidateMapper;
        this.t1Mapper = t1Mapper;
        this.configService = configService;
    }

    public Map<String, Object> review(Long strategyId, LocalDate from, LocalDate to) {
        WaveRiderConfig cfg = configService.currentConfig(strategyId);
        List<CandidateT1> rows = t1Mapper.selectList(new LambdaQueryWrapper<CandidateT1>()
                .eq(CandidateT1::getStrategyId, strategyId)
                .ge(CandidateT1::getTradeDate, from)
                .le(CandidateT1::getTradeDate, to)
                .orderByAsc(CandidateT1::getTradeDate));

        // 按 (date, code) 找候选行，为了拿原则/节点/风险标记做分组
        List<CandidateStock> cands = candidateMapper.selectList(new LambdaQueryWrapper<CandidateStock>()
                .eq(CandidateStock::getStrategyId, strategyId)
                .ge(CandidateStock::getTradeDate, from)
                .le(CandidateStock::getTradeDate, to));
        Map<String, CandidateStock> candIndex = new LinkedHashMap<>();
        for (CandidateStock c : cands) {
            candIndex.put(c.getTradeDate() + "|" + c.getCode(), c);
        }

        List<CandidateT1> usable = new ArrayList<>();
        for (CandidateT1 r : rows) {
            if (r.getT1ChangePct() != null) {
                usable.add(r);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("candidateCount", cands.size());
        out.put("verifiedCount", usable.size());
        out.put("entryGapMax", cfg.getEntryGapMax());
        out.put("entryPriceBasis", cfg.getEntryPriceBasis());

        out.put("overall", stats(usable));
        out.put("buyable", stats(filterByGap(usable, cfg.getEntryGapMax(), true)));
        out.put("tooHighOpen", stats(filterByGap(usable, cfg.getEntryGapMax(), false)));
        out.put("gapBuckets", gapBuckets(usable));
        out.put("bySealBucket", sealBuckets(usable, candIndex));
        out.put("byPrinciple", groupBy(usable, candIndex, true));
        out.put("byNodeType", groupBy(usable, candIndex, false));
        return out;
    }

    /** 单个样本集合的汇总。A 与 B 两个口径都给，别只取一个。 */
    public Map<String, Object> stats(List<CandidateT1> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", rows.size());
        if (rows.isEmpty()) {
            return m;
        }
        int promoted = 0;
        double sumA = 0;
        double sumB = 0;
        double sumGap = 0;
        int gapN = 0;
        int winA = 0;
        int winB = 0;
        int lossB = 0;
        double sumWinB = 0;
        double sumLossB = 0;
        for (CandidateT1 r : rows) {
            if (r.getPromoted() != null && r.getPromoted() == 1) {
                promoted++;
            }
            double a = r.getT1ChangePct().doubleValue();
            sumA += a;
            if (a > 0) {
                winA++;
            }
            if (r.getGapPct() != null) {
                sumGap += r.getGapPct().doubleValue();
                gapN++;
            }
            Double b = bPct(r);
            if (b != null) {
                sumB += b;
                if (b > 0) {
                    winB++;
                    sumWinB += b;
                } else if (b < 0) {
                    lossB++;
                    sumLossB += -b;
                }
            }
        }
        m.put("promoteRate", pct2(promoted * 100.0 / rows.size()));
        m.put("avgChangeA", pct2(sumA / rows.size()));
        m.put("winRateA", pct2(winA * 100.0 / rows.size()));
        m.put("avgChangeB", pct2(sumB / rows.size()));
        m.put("winRateB", pct2(winB * 100.0 / rows.size()));
        m.put("avgGap", gapN == 0 ? null : pct2(sumGap / gapN));
        // 盈亏比 = 盈利样本平均涨幅 ÷ 亏损样本平均跌幅的绝对值
        m.put("profitLossRatio", lossB == 0 || sumLossB == 0
                ? null : pct2((sumWinB / Math.max(winB, 1)) / (sumLossB / lossB)));
        return m;
    }

    /** B 口径（可执行）收益 %。由 A 口径与跳空推出来，不需要再回上游取价。 */
    public Double bPct(CandidateT1 r) {
        if (r.getT1ChangePct() == null || r.getGapPct() == null) {
            return null;
        }
        double t1 = 1 + r.getT1ChangePct().doubleValue() / 100.0;
        double gap = 1 + r.getGapPct().doubleValue() / 100.0;
        if (gap == 0) {
            return null;
        }
        return (t1 / gap - 1) * 100;
    }

    /**
     * 按封单额÷成交额分五档复盘。这是「封单越强、A 口径越赚」在页面上的直接证据，
     * 也正是把封单锁死从「剔除」改成「置顶」的依据本身。
     */
    private List<Map<String, Object>> sealBuckets(List<CandidateT1> rows,
                                                  Map<String, CandidateStock> index) {
        double[][] edges = {{0, 0.15}, {0.15, 0.5}, {0.5, 1.5}, {1.5, 3.0}, {3.0, Double.MAX_VALUE}};
        String[] labels = {"< 15%", "15 ~ 50%", "50 ~ 150%", "150 ~ 300%", "≥ 300%（多为一字）"};
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < edges.length; i++) {
            List<CandidateT1> sub = new ArrayList<>();
            for (CandidateT1 r : rows) {
                Double sr = sealRatioOf(r, index);
                if (sr != null && sr >= edges[i][0] && sr < edges[i][1]) {
                    sub.add(r);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>(stats(sub));
            m.put("label", labels[i]);
            out.add(m);
        }
        return out;
    }

    /** 封单比存在候选行的 filterDetailJson 里；取不到就是 null，不猜。 */
    private Double sealRatioOf(CandidateT1 r, Map<String, CandidateStock> index) {
        CandidateStock c = index.get(r.getTradeDate() + "|" + r.getCode());
        if (c == null || c.getFilterDetailJson() == null) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(c.getFilterDetailJson()).get("seal_ratio");
            return n == null || n.isNull() ? null : n.asDouble();
        } catch (Exception e) {
            return null;
        }
    }

    private List<CandidateT1> filterByGap(List<CandidateT1> rows, double max, boolean within) {
        List<CandidateT1> out = new ArrayList<>();
        for (CandidateT1 r : rows) {
            if (r.getGapPct() == null) {
                continue;
            }
            double g = r.getGapPct().doubleValue() / 100.0;
            if (within ? g <= max : g > max) {
                out.add(r);
            }
        }
        return out;
    }

    /** 跳空分档：这是「买不买得到」的画像，也是 B 口径收益最强的解释变量。 */
    private List<Map<String, Object>> gapBuckets(List<CandidateT1> rows) {
        String[] labels = {"< 0%（低开）", "0 ~ 3%", "3 ~ 8%", "≥ 8%（含一字）"};
        double[][] ranges = {{Double.NEGATIVE_INFINITY, 0}, {0, 3}, {3, 8}, {8, Double.POSITIVE_INFINITY}};
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            List<CandidateT1> bucket = new ArrayList<>();
            for (CandidateT1 r : rows) {
                if (r.getGapPct() == null) {
                    continue;
                }
                double g = r.getGapPct().doubleValue();
                if (g >= ranges[i][0] && g < ranges[i][1]) {
                    bucket.add(r);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", labels[i]);
            m.putAll(stats(bucket));
            out.add(m);
        }
        return out;
    }

    /** 按命中原则 / 节点类型分组，用于校准 W_position、W_node 与 node_type_weights。 */
    private Map<String, Object> groupBy(List<CandidateT1> rows, Map<String, CandidateStock> index,
                                        boolean byPrinciple) {
        Map<String, List<CandidateT1>> groups = new LinkedHashMap<>();
        for (CandidateT1 r : rows) {
            CandidateStock c = index.get(r.getTradeDate() + "|" + r.getCode());
            String key;
            if (c == null) {
                key = "未关联";
            } else if (byPrinciple) {
                key = c.getHitPrinciplesJson() == null ? "未标注" : c.getHitPrinciplesJson();
            } else {
                key = c.getNodeType() == null ? "非节点日" : c.getNodeType();
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<CandidateT1>> e : groups.entrySet()) {
            out.put(e.getKey(), stats(e.getValue()));
        }
        return out;
    }

    private Double pct2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
