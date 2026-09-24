package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * <p><strong>这个类里所有收益都同时给两个口径，且默认口径是 B（可执行）</strong>：
 * <ul>
 *   <li><b>A 口径</b> {@code close(D+1)/close(D) − 1}：候选池的定义是「D 日已涨停」，
 *       所以 D 日收盘价在实盘买不到。它描述的是信号强度，不是能拿到的钱。</li>
 *   <li><b>B 口径</b> {@code close(D+1)/open(D+1) − 1}：以 D+1 开盘价起算，
 *       这是唯一可成交的基准。</li>
 * </ul>
 * 两者实测差得极远（同一批 226 个样本：A +3.36% / B −0.20%），差在隔夜跳空里。
 * 界面上如果只摆 A 口径，会得到一个系统性乐观的结论——这正是 PRD v1.4 要修的那个坑。
 *
 * <p>另外单列 {@code gapBuckets}：隔夜跳空的分布。它是「买不买得到」的画像，
 * 而且用 B 口径算出的收益对跳空高度高度敏感（跳空越小越划算），
 * 所以这个分档比任何单因子 IC 都更能解释组合表现。
 */
@Service
public class WaveRiderReviewService {

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
