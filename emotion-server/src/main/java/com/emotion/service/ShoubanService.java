package com.emotion.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.MarketMetrics;
import com.emotion.market.StockPatterns;
import com.emotion.vo.ShoubanVO;

/**
 * 首板池（PRD P3）：今日首板「封住」与「炸板」两表 + 1 进 2 晋级统计。
 *
 * <p>封住 = 涨停池 1 板行；炸板 = 炸板池中<b>昨日未在涨停池</b>的行——昨日明细缺失时
 * 无法区分"首板尝试"和"连板尝试"，此时炸板表返回空而不是猜测，prevAvailable=false 交给前端提示。
 * 昨日首板今日均溢价直接读档位表 board=1 档（与打分第 4 维同一个数，不另算一份）。
 */
@Service
public class ShoubanService {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final MarketStockMapper marketStockMapper;
    private final PremiumTierStore premiumTierStore;

    public ShoubanService(MarketStockMapper marketStockMapper, PremiumTierStore premiumTierStore) {
        this.marketStockMapper = marketStockMapper;
        this.premiumTierStore = premiumTierStore;
    }

    public ShoubanVO vo(Long userId, LocalDate requested) {
        LocalDate date = requested != null ? requested : LocalDate.now(CN);
        List<MarketStock> todayZT = listPool(date, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> todayZB = listPool(date, MarketStock.POOL_BROKEN);

        LocalDate prev = marketStockMapper.prevDetailDate(date);
        Map<String, Integer> prevBoard = new HashMap<>();
        boolean prevAvailable = prev != null;
        List<MarketStock> prevZT = new ArrayList<>();
        if (prevAvailable) {
            prevZT = listPool(prev, MarketStock.POOL_LIMIT_UP);
            for (MarketStock row : prevZT) {
                prevBoard.put(row.getCode(), row.getConsecutive() == null ? 1 : row.getConsecutive());
            }
        }

        String main = topIndustry(todayZT);

        ShoubanVO vo = new ShoubanVO();
        vo.setTradeDate(date);
        vo.setPrevDate(prev);
        vo.setPrevAvailable(prevAvailable);

        List<ShoubanVO.Row> sealed = new ArrayList<>();
        for (MarketStock row : todayZT) {
            int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (n != 1) {
                continue;
            }
            sealed.add(rowOf(row, main));
        }
        vo.setSealed(sealed);

        List<ShoubanVO.Row> bombed = new ArrayList<>();
        if (prevAvailable) {
            for (MarketStock row : todayZB) {
                if (prevBoard.containsKey(row.getCode())) {
                    continue; // 昨日已在涨停池：今日是连板尝试，归连板天梯/炸板质量口径，不进首板池
                }
                bombed.add(rowOf(row, main));
            }
        }
        vo.setBombed(bombed);

        ShoubanVO.Summary s = new ShoubanVO.Summary();
        s.setSealedCount(sealed.size());
        s.setBombedCount(bombed.size());
        int denom = sealed.size() + bombed.size();
        s.setSealedRate(denom == 0 ? null : PrdMetricsService.pct(sealed.size(), denom));
        s.setPrevFirstCount(prevBoard.size() == 0 ? 0 : countFirst(prevBoard));
        int promo = 0;
        for (MarketStock row : todayZT) {
            Integer n = row.getConsecutive();
            if (n != null && n == 2 && prevBoard.containsKey(row.getCode())
                    && prevBoard.get(row.getCode()) == 1) {
                promo++;
            }
        }
        s.setPromoCount(promo);
        s.setPromoRate(prevBoard.isEmpty() || s.getPrevFirstCount() == 0
                ? null : PrdMetricsService.pct(promo, s.getPrevFirstCount()));
        s.setPrevFirstPremiumPct(firstPremium(date));
        vo.setSummary(s);

        vo.setPromoDetail(promoDetail(date, prevAvailable, todayZT, todayZB, prevZT, prevBoard, main));
        return vo;
    }

    /**
     * 1 进 2 成绩单（=「首板溢价」的逐只口径）：昨日首板的每一只，今天要么封上 2 板（成功），
     * 要么停在 1 板/炸板/未触板（失败）。逐只涨跌幅只覆盖涨停/炸板池，未触板的不编数；
     * 全样本均溢价仍以档位表 board=1（实时快照采集）为准。
     */
    private ShoubanVO.PromoDetail promoDetail(LocalDate date, boolean prevAvailable,
                                              List<MarketStock> todayZT, List<MarketStock> todayZB,
                                              List<MarketStock> prevZT,
                                              Map<String, Integer> prevBoard, String main) {
        ShoubanVO.PromoDetail p = new ShoubanVO.PromoDetail();
        p.setSuccess(new ArrayList<ShoubanVO.Row>());
        p.setFailed(new ArrayList<ShoubanVO.FailedRow>());
        p.setAvgPremiumPct(firstPremium(date));
        if (!prevAvailable) {
            p.setPrevCount(0);
            p.setPromotedCount(0);
            return p;
        }
        int prevFirst = countFirst(prevBoard);
        p.setPrevCount(prevFirst);

        java.util.Set<String> promotedCodes = new java.util.HashSet<>();
        for (MarketStock row : todayZT) {
            Integer n = row.getConsecutive();
            Integer prevN = prevBoard.get(row.getCode());
            if (n != null && n == 2 && prevN != null && prevN == 1) {
                p.getSuccess().add(rowOf(row, main));
                promotedCodes.add(row.getCode());
            }
        }
        // 失败：昨日首板里今天没封 2 板的，逐只判今日去向
        for (MarketStock prev : prevZT) {
            int pb = prev.getConsecutive() == null ? 1 : prev.getConsecutive();
            if (pb != 1 || promotedCodes.contains(prev.getCode())) {
                continue;
            }
            ShoubanVO.FailedRow f = new ShoubanVO.FailedRow();
            f.setCode(prev.getCode());
            f.setName(prev.getName());
            f.setIndustry(prev.getIndustry());
            MarketStock zt = findByCode(todayZT, prev.getCode());
            MarketStock zb = findByCode(todayZB, prev.getCode());
            if (zt != null) {
                f.setTodayStatus("ZT");
                f.setChangePct(zt.getChangePct());
                f.setPattern(StockPatterns.of(zt));
            } else if (zb != null) {
                f.setTodayStatus("ZB");
                f.setChangePct(zb.getChangePct());
                f.setPullbackPct(zb.getPullbackPct());
            } else {
                f.setTodayStatus("GONE");
            }
            p.getFailed().add(f);
        }
        p.setPromotedCount(p.getSuccess().size());
        p.setPromoRate(prevFirst == 0 ? null : PrdMetricsService.pct(p.getSuccess().size(), prevFirst));
        return p;
    }

    private static MarketStock findByCode(List<MarketStock> rows, String code) {
        for (MarketStock row : rows) {
            if (code != null && code.equals(row.getCode())) {
                return row;
            }
        }
        return null;
    }

    private static ShoubanVO.Row rowOf(MarketStock row, String main) {
        ShoubanVO.Row r = new ShoubanVO.Row();
        r.setCode(row.getCode());
        r.setName(row.getName());
        r.setIndustry(row.getIndustry());
        r.setChangePct(row.getChangePct());
        r.setPullbackPct(row.getPullbackPct());
        r.setBreakCount(row.getBreakCount());
        r.setSealAmount(row.getSealAmount());
        r.setFirstSealTime(row.getFirstSealTime());
        r.setPattern(StockPatterns.of(row));
        r.setInMain(main != null && main.equals(row.getIndustry()));
        return r;
    }

    /** 昨日首板家数：昨日涨停池中连板数=1 的行数（键值即连板数）。 */
    private static int countFirst(Map<String, Integer> prevBoard) {
        int n = 0;
        for (Integer b : prevBoard.values()) {
            if (b != null && b == 1) {
                n++;
            }
        }
        return n;
    }

    /** 档位表 board=1 档的均溢价 = "昨日首板今日表现"；没落档位（含 0 家匹配）返回 null。 */
    private Double firstPremium(LocalDate date) {
        MarketMetrics.PremiumTiers tiers = premiumTierStore.read(date);
        if (tiers == null || tiers.getTiers() == null) {
            return null;
        }
        for (MarketMetrics.TierPremium t : tiers.getTiers()) {
            if (t.getBoard() == 1) {
                return t.getAvgPct() == null ? null : t.getAvgPct().doubleValue();
            }
        }
        return null;
    }

    private static String topIndustry(List<MarketStock> zt) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MarketStock row : zt) {
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

    private List<MarketStock> listPool(LocalDate date, String pool) {
        return marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, pool));
    }
}
