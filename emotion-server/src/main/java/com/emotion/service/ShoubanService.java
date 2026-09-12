package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.emotion.market.StockPatterns;
import com.emotion.vo.ShoubanVO;

/**
 * 首板生态（时间截面 PRD v2.0，纯 T 日试错端）：今日首板「封住」与「炸板」两表 + T 日 summary。
 *
 * <p>封住 = 涨停池 1 板行；炸板 = 炸板池中<b>昨日未在涨停池</b>的行——昨日明细缺失时
 * 无法区分"首板尝试"和"连板尝试"，此时炸板表返回空而不是猜测，prevAvailable=false 交给前端提示。
 * 1进2晋级/首板溢价/1进2大面是 T-1→T 兑现口径，已迁至连板生态低位层（见 LadderMetricsService 与 /tianti）。
 */
@Service
public class ShoubanService {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final MarketStockMapper marketStockMapper;

    public ShoubanService(MarketStockMapper marketStockMapper) {
        this.marketStockMapper = marketStockMapper;
    }

    public ShoubanVO vo(Long userId, LocalDate requested) {
        LocalDate date = requested != null ? requested : LocalDate.now(CN);
        List<MarketStock> todayZT = listPool(date, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> todayZB = listPool(date, MarketStock.POOL_BROKEN);

        LocalDate prev = marketStockMapper.prevDetailDate(date);
        Map<String, Integer> prevBoard = new HashMap<>();
        boolean prevAvailable = prev != null;
        if (prevAvailable) {
            for (MarketStock row : listPool(prev, MarketStock.POOL_LIMIT_UP)) {
                prevBoard.put(row.getCode(), row.getConsecutive() == null ? 1 : row.getConsecutive());
            }
        }
        String main = topIndustry(todayZT);

        ShoubanVO vo = new ShoubanVO();
        vo.setTradeDate(date);
        vo.setPrevAvailable(prevAvailable);

        List<ShoubanVO.Row> sealed = new ArrayList<>();
        List<MarketStock> firstRows = new ArrayList<>();
        for (MarketStock row : todayZT) {
            int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (n != 1) {
                continue;
            }
            sealed.add(rowOf(row, main));
            firstRows.add(row);
        }
        vo.setSealed(sealed);

        List<ShoubanVO.Row> bombed = new ArrayList<>();
        if (prevAvailable) {
            for (MarketStock row : todayZB) {
                if (prevBoard.containsKey(row.getCode())) {
                    continue; // 昨日已在涨停池：今日是连板尝试，不进首板池
                }
                bombed.add(rowOf(row, main));
            }
        }
        vo.setBombed(bombed);

        vo.setSummary(summary(firstRows, bombed.size(), prevAvailable));
        return vo;
    }

    /** 纯 T 日汇总：封板/炸板率、一字占比、均封单、题材聚集（与 LadderMetricsService 同口径）。 */
    private ShoubanVO.Summary summary(List<MarketStock> firstRows, int bombed, boolean prevAvailable) {
        ShoubanVO.Summary s = new ShoubanVO.Summary();
        int sealed = firstRows.size();
        s.setSealedCount(sealed);
        s.setBombedCount(bombed);
        if (prevAvailable && sealed + bombed > 0) {
            s.setSealedRate(PrdMetricsService.pct(sealed, sealed + bombed));
            s.setBombRate(PrdMetricsService.pct(bombed, sealed + bombed));
        }
        // 一字家数/占比：只在能判形态（有首封时间）的样本里统计
        int patternN = 0;
        int yizi = 0;
        BigDecimal sealSum = BigDecimal.ZERO;
        int sealN = 0;
        Map<String, Integer> byIndustry = new LinkedHashMap<>();
        int industryN = 0;
        for (MarketStock row : firstRows) {
            if (row.getFirstSealTime() != null) {
                patternN++;
                if (StockPatterns.ONE_LINE.equals(StockPatterns.of(row))) {
                    yizi++;
                }
            }
            if (row.getSealAmount() != null) {
                sealSum = sealSum.add(row.getSealAmount());
                sealN++;
            }
            String ind = row.getIndustry();
            if (ind != null && !ind.trim().isEmpty()) {
                byIndustry.merge(ind, 1, Integer::sum);
                industryN++;
            }
        }
        s.setYiziCount(yizi);
        if (patternN > 0) {
            s.setYiziRatio(new BigDecimal(yizi * 100)
                    .divide(BigDecimal.valueOf(patternN), 2, RoundingMode.HALF_UP).doubleValue());
        }
        if (sealN > 0) {
            s.setAvgSealAmount(sealSum.divide(BigDecimal.valueOf(sealN), 0, RoundingMode.HALF_UP));
        }
        String top = null;
        int topN = 0;
        for (Map.Entry<String, Integer> e : byIndustry.entrySet()) {
            if (e.getValue() > topN) {
                topN = e.getValue();
                top = e.getKey();
            }
        }
        s.setTopIndustry(top);
        s.setTopIndustryCount(topN == 0 ? null : topN);
        if (industryN > 0) {
            s.setThemeGatherPct(PrdMetricsService.pct(topN, industryN));
        }
        return s;
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
