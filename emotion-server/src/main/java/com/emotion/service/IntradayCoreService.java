package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.StockPatterns;
import com.emotion.vo.IntradayCoreVO;

/**
 * 日内核心（PRD 页面A）：当日涨停/炸板两池按 {@code industry}（东财 hybk 行业，非概念题材）聚合，
 * 算板块强度分并排名；板块下钻返回该行业涨停池逐只。
 *
 * <p>数据等价 SQL（聚合在 Java 做是为了与 {@link PrdMetricsService} 同模式——纯函数可喂内存 fixture 单测）：
 * <pre>
 * SELECT industry, COUNT(*) lb_count, MAX(IFNULL(consecutive,1)) max_board,
 *        SUM(seal_amount) seal_sum,
 *        SUM(first_seal_time IS NOT NULL AND first_seal_time&lt;=93000 AND IFNULL(break_count,0)=0) yizi_cnt,
 *        SUM(IFNULL(break_count,0)&gt;0) reopen_cnt,
 *        COUNT(DISTINCT IFNULL(consecutive,1)) tier_cnt
 * FROM t_market_stock WHERE trade_date=? AND pool='ZT' AND industry IS NOT NULL AND industry&lt;&gt;''
 * GROUP BY industry;
 * -- 炸板/大面另取 pool='ZB' 同 GROUP BY；大面只可能出现在炸板池（封死的涨停不满足"回撤&gt;7%且收绿"）。
 * </pre>
 *
 * <p><b>取不到不兜 0 的边界</b>：seal_amount 整列缺失时封单分大家都是 0（平等缺数）；
 * fbt 历史缺失的行不算一字也不算回封之外的别的形态，一字数可能偏小——这是"未知"而不是 0。
 */
@Service
public class IntradayCoreService {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /** 强度分权重：家数 25% / 高度 25% / 封单 20% / 一字占比 15% / 梯队完整性 15%。 */
    static final double W_COUNT = 0.25;
    static final double W_HEIGHT = 0.25;
    static final double W_SEAL = 0.20;
    static final double W_YIZI = 0.15;
    static final double W_TIER = 0.15;
    /** 每只大面扣 10 分，封顶 30。 */
    static final int BIG_LOSS_PENALTY_EACH = 10;
    static final int BIG_LOSS_PENALTY_CAP = 30;

    static final String SORT_STRENGTH = "strength";
    static final String SORT_LB = "lb";
    static final String SORT_BOARD = "board";
    static final String SORT_SEAL = "seal";

    private final MarketStockMapper marketStockMapper;

    public IntradayCoreService(MarketStockMapper marketStockMapper) {
        this.marketStockMapper = marketStockMapper;
    }

    /** 排名快照。sort：strength(默认)/lb/board/seal。 */
    public IntradayCoreVO core(LocalDate requested, String sort) {
        LocalDate date = requested != null ? requested : LocalDate.now(CN);
        List<MarketStock> zt = listPool(date, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> zb = listPool(date, MarketStock.POOL_BROKEN);
        return aggregate(date, zt, zb, sort);
    }

    /** 板块下钻：该行业当日涨停池，连板高→低（同板涨幅高→低、再同代码升序）。 */
    public IntradayCoreVO.SectorStocks sectorStocks(LocalDate requested, String industry) {
        LocalDate date = requested != null ? requested : LocalDate.now(CN);
        IntradayCoreVO.SectorStocks out = new IntradayCoreVO.SectorStocks();
        out.setTradeDate(date);
        out.setIndustry(industry);
        if (industry == null || industry.trim().isEmpty()) {
            out.setAvailable(false);
            return out;
        }
        List<MarketStock> rows = new ArrayList<>(listPool(date, MarketStock.POOL_LIMIT_UP));
        rows.removeIf(r -> !industry.equals(r.getIndustry()));
        rows.sort(BOARD_ORDER);
        for (MarketStock row : rows) {
            out.getStocks().add(item(row));
        }
        out.setAvailable(!out.getStocks().isEmpty());
        return out;
    }

    /**
     * 纯聚合（不碰 DB），包私有以便单测直接喂 fixture。
     *
     * @param zt 当日全部涨停池行（含 industry 为空的——它们进 ztTotal/globalMaxBoard 但不进排名）
     * @param zb 当日全部炸板池行
     */
    IntradayCoreVO aggregate(LocalDate date, List<MarketStock> zt, List<MarketStock> zb, String sort) {
        IntradayCoreVO vo = new IntradayCoreVO();
        vo.setTradeDate(date);
        vo.setZtTotal(zt.size());
        vo.setZbTotal(zb.size());
        vo.setAvailable(!zt.isEmpty());
        if (zt.isEmpty()) {
            return vo;
        }

        // 全市场高度 H（含无行业行），板块高度分的分母
        int globalH = 0;
        for (MarketStock row : zt) {
            globalH = Math.max(globalH, board(row));
        }
        vo.setGlobalMaxBoard(globalH);

        // 涨停池按行业聚合
        Map<String, SectorAgg> byIndustry = new LinkedHashMap<>();
        for (MarketStock row : zt) {
            String ind = row.getIndustry();
            if (ind == null || ind.trim().isEmpty()) {
                continue;
            }
            SectorAgg agg = byIndustry.get(ind);
            if (agg == null) {
                agg = new SectorAgg(ind);
                byIndustry.put(ind, agg);
            }
            agg.addZT(row);
        }
        // 炸板/大面按行业挂上去（ZT 里没出现的行业不进排名）
        for (MarketStock row : zb) {
            String ind = row.getIndustry();
            if (ind == null || ind.trim().isEmpty()) {
                continue;
            }
            SectorAgg agg = byIndustry.get(ind);
            if (agg != null) {
                agg.addZB(row);
            }
        }

        // 归一化分母：当日最大行业家数、最大封单额
        int maxLb = 0;
        BigDecimal maxSeal = BigDecimal.ZERO;
        for (SectorAgg agg : byIndustry.values()) {
            maxLb = Math.max(maxLb, agg.lbCount);
            if (agg.sealSum.compareTo(maxSeal) > 0) {
                maxSeal = agg.sealSum;
            }
        }

        List<IntradayCoreVO.Sector> sectors = new ArrayList<>();
        for (SectorAgg agg : byIndustry.values()) {
            sectors.add(agg.toSector(globalH, maxLb, maxSeal));
        }
        sectors.sort(sortComparator(sort));
        vo.setSectors(sectors);
        return vo;
    }

    // ================= 纯聚合内部类型 =================

    private static class SectorAgg {
        final String industry;
        int lbCount;
        int maxBoard;
        BigDecimal sealSum = BigDecimal.ZERO;
        int yiziCnt;
        int reopenCnt;
        int zbCnt;
        int bigLossCnt;
        // 板层 -> 占位；用 TreeMap 高→低输出
        final Map<Integer, Boolean> tiers = new LinkedHashMap<>();
        MarketStock leader;

        SectorAgg(String industry) {
            this.industry = industry;
        }

        void addZT(MarketStock row) {
            lbCount++;
            int b = board(row);
            if (b > maxBoard) {
                maxBoard = b;
            }
            tiers.put(b, Boolean.TRUE);
            if (row.getSealAmount() != null) {
                sealSum = sealSum.add(row.getSealAmount());
            }
            // 一字/T字判定与全系统唯一口径保持一致（StockPatterns），fbt 缺失=未知，不算一字
            if (StockPatterns.ONE_LINE.equals(StockPatterns.of(row))) {
                yiziCnt++;
            }
            if (row.getBreakCount() != null && row.getBreakCount() > 0) {
                reopenCnt++;
            }
            if (leader == null || BOARD_ORDER.compare(row, leader) < 0) {
                leader = row;
            }
        }

        void addZB(MarketStock row) {
            zbCnt++;
            if (row.getBigLoss() != null && row.getBigLoss() == 1) {
                bigLossCnt++;
            }
        }

        IntradayCoreVO.Sector toSector(int globalH, int maxLb, BigDecimal maxSeal) {
            IntradayCoreVO.Sector s = new IntradayCoreVO.Sector();
            s.setIndustry(industry);
            s.setLbCount(lbCount);
            s.setMaxBoard(maxBoard);
            s.setSealSum(sealSum.setScale(2, RoundingMode.HALF_UP));
            s.setYiziCnt(yiziCnt);
            s.setReopenCnt(reopenCnt);
            s.setZbCnt(zbCnt);
            s.setBigLossCnt(bigLossCnt);
            s.setTierCnt(tiers.size());
            List<Integer> tierList = new ArrayList<>(tiers.keySet());
            tierList.sort(Comparator.reverseOrder());
            s.setTiers(tierList);
            if (leader != null) {
                s.setLeaderCode(leader.getCode());
                s.setLeaderName(leader.getName());
                s.setLeaderBoard(board(leader));
            }

            double cntScore = scaled(100.0 * lbCount / maxLb);
            double hScore = scaled(100.0 * maxBoard / globalH);
            double sealScore = maxSeal.signum() > 0
                    ? scaled(sealSum.doubleValue() * 100.0 / maxSeal.doubleValue()) : 0;
            double yiziScore = scaled(100.0 * yiziCnt / lbCount);
            // 梯队分母=2..maxBoard 共 maxBoard-1 层；maxBoard=1（全首板）时单层即满，给 100。
            // tierCnt 含 1 板层（COUNT DISTINCT consecutive），完整梯队 1..H 时自然封顶 100。
            double tierScore = maxBoard <= 1
                    ? 100
                    : scaled(Math.min(100, 100.0 * tiers.size() / (maxBoard - 1)));
            int penalty = Math.min(BIG_LOSS_PENALTY_CAP, bigLossCnt * BIG_LOSS_PENALTY_EACH);
            double raw = W_COUNT * cntScore + W_HEIGHT * hScore + W_SEAL * sealScore
                    + W_YIZI * yiziScore + W_TIER * tierScore - penalty;

            s.setCntScore(cntScore);
            s.setHScore(hScore);
            s.setSealScore(sealScore);
            s.setYiziScore(yiziScore);
            s.setTierScore(tierScore);
            s.setPenalty(penalty);
            s.setStrength(BigDecimal.valueOf(Math.max(0, Math.min(100, raw)))
                    .setScale(2, RoundingMode.HALF_UP));
            return s;
        }
    }

    // ================= helpers =================

    /** 涨停池行排序：连板高→低，同板涨幅高→低（null 垫后），再同代码升序。龙头选取复用同一顺序。 */
    private static final Comparator<MarketStock> BOARD_ORDER = (a, b) -> {
        int cmp = Integer.compare(board(b), board(a));
        if (cmp != 0) {
            return cmp;
        }
        BigDecimal pa = a.getChangePct();
        BigDecimal pb = b.getChangePct();
        if (pa != null || pb != null) {
            if (pa == null) {
                return 1;
            }
            if (pb == null) {
                return -1;
            }
            cmp = pb.compareTo(pa);
            if (cmp != 0) {
                return cmp;
            }
        }
        String ca = a.getCode() == null ? "" : a.getCode();
        String cb = b.getCode() == null ? "" : b.getCode();
        return ca.compareTo(cb);
    };

    private static Comparator<IntradayCoreVO.Sector> sortComparator(String sort) {
        Comparator<IntradayCoreVO.Sector> primary;
        if (SORT_LB.equals(sort)) {
            primary = Comparator.comparingInt(IntradayCoreVO.Sector::getLbCount).reversed();
        } else if (SORT_BOARD.equals(sort)) {
            primary = Comparator.comparingInt(IntradayCoreVO.Sector::getMaxBoard).reversed();
        } else if (SORT_SEAL.equals(sort)) {
            primary = Comparator.comparing(IntradayCoreVO.Sector::getSealSum).reversed();
        } else {
            primary = Comparator.comparing(IntradayCoreVO.Sector::getStrength).reversed();
        }
        // 同分确定性次序：家数、高度、行业名
        return primary
                .thenComparing(Comparator.comparingInt(IntradayCoreVO.Sector::getLbCount).reversed())
                .thenComparing(Comparator.comparingInt(IntradayCoreVO.Sector::getMaxBoard).reversed())
                .thenComparing(IntradayCoreVO.Sector::getIndustry);
    }

    private static IntradayCoreVO.Item item(MarketStock row) {
        IntradayCoreVO.Item item = new IntradayCoreVO.Item();
        item.setCode(row.getCode());
        item.setName(row.getName());
        item.setIndustry(row.getIndustry());
        item.setBoard(board(row));
        item.setChangePct(row.getChangePct());
        item.setSealAmount(row.getSealAmount());
        item.setFirstSealTime(row.getFirstSealTime());
        item.setBreakCount(row.getBreakCount());
        item.setPattern(StockPatterns.of(row));
        return item;
    }

    private static int board(MarketStock row) {
        return row.getConsecutive() == null ? 1 : row.getConsecutive();
    }

    private static double scaled(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private List<MarketStock> listPool(LocalDate date, String pool) {
        return marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, pool));
    }
}
