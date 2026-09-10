package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.StockPatterns;
import com.emotion.vo.TiantiVO;

/**
 * 连板生态（PRD P2）：当日涨停池按<b>板高</b>从 H 到 2 逐层组成金字塔，
 * 每层给个股（龙头分工标签 + 一字/T字/换手形态 + 封单额）与晋级明细
 * （昨日 n-1 板家数、晋级成功/失败名单、晋级率）。
 *
 * <p>四层动态归属（低=2/中=3-4/中高=5..hsplit/极高=hsplit+1..H）仍按
 * {@link LadderMetricsService#hsplit}/{@link LadderMetricsService#layerIndex} 打在每层上，
 * 保证金字塔的层名与打分四层对得上。龙头标签与判定依据全部取自 {@link PrdMetricsService} 同一份快照。
 */
@Service
public class TiantiService {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final String[] LAYER_LABELS = {"低位", "中位", "中高位", "极高位"};

    private final PrdMetricsService prdMetrics;
    private final MarketStockMapper marketStockMapper;

    public TiantiService(PrdMetricsService prdMetrics, MarketStockMapper marketStockMapper) {
        this.prdMetrics = prdMetrics;
        this.marketStockMapper = marketStockMapper;
    }

    public TiantiVO vo(Long userId, LocalDate requested) {
        LocalDate date = requested != null ? requested : LocalDate.now(CN);
        PrdMetricsService.Snapshot snap = prdMetrics.snapshot(userId, date);

        List<MarketStock> zt = listPool(date, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> zb = listPool(date, MarketStock.POOL_BROKEN);
        LocalDate prev = marketStockMapper.prevDetailDate(date);
        List<MarketStock> prevZT = prev == null ? new ArrayList<MarketStock>()
                : listPool(prev, MarketStock.POOL_LIMIT_UP);
        Map<String, Integer> prevBoard = new HashMap<>();
        for (MarketStock row : prevZT) {
            prevBoard.put(row.getCode(), row.getConsecutive() == null ? 1 : row.getConsecutive());
        }
        // 今日个股按代码索引（涨停 + 炸板），判晋级失败后的去向
        Map<String, MarketStock> todayZtByCode = new LinkedHashMap<>();
        for (MarketStock row : zt) {
            todayZtByCode.put(row.getCode(), row);
        }
        Map<String, MarketStock> todayZbByCode = new LinkedHashMap<>();
        for (MarketStock row : zb) {
            todayZbByCode.put(row.getCode(), row);
        }

        TiantiVO vo = new TiantiVO();
        vo.setTradeDate(date);
        vo.setMaxBoard(snap.maxBoard);
        vo.setMainIndustry(snap.mainIndustry);
        vo.setMainlineConfirmed(snap.mainlineConfirmed);
        vo.setZtGatherPct(snap.ztGatherPct);
        vo.setHeightGatherPct(snap.heightGatherPct);
        vo.setPersistenceDays(snap.persistenceDays);
        vo.setZtTotal(snap.ztTotal);
        vo.setZbTotal(snap.zbTotal);
        vo.setDragon(dragon(snap));

        Set<String> zong = new HashSet<>();
        if (snap.zongLong != null && snap.zongLong.getCode() != null) {
            zong.add(snap.zongLong.getCode());
        }
        Set<String> zhongJun = codes(snap.zhongJun);
        Set<String> fanBao = codes(snap.fanBao);
        String kaWeiCode = snap.kaWei == null ? null : snap.kaWei.getCode();

        int h = Math.max(snap.maxBoard, 2);

        // 金字塔：n 从 H 往下到 2，每层独立判晋级
        List<TiantiVO.Level> levels = new ArrayList<>();
        for (int n = h; n >= 2; n--) {
            levels.add(level(n, h, zt, prevZT, prevBoard, todayZtByCode, todayZbByCode, snap,
                    zong, zhongJun, kaWeiCode, fanBao));
        }
        vo.setLevels(levels);
        return vo;
    }

    private TiantiVO.Level level(int n, int h,
                                 List<MarketStock> zt, List<MarketStock> prevZT,
                                 Map<String, Integer> prevBoard,
                                 Map<String, MarketStock> todayZtByCode,
                                 Map<String, MarketStock> todayZbByCode,
                                 PrdMetricsService.Snapshot snap,
                                 Set<String> zong, Set<String> zhongJun, String kaWeiCode, Set<String> fanBao) {
        TiantiVO.Level lvl = new TiantiVO.Level();
        lvl.setBoard(n);
        lvl.setLayerLabel(LAYER_LABELS[LadderMetricsService.layerIndex(n, h)]);

        // 今日 n 板个股（板高降序、同板高涨幅降序由 zt 顺序保证——zt 按代码入库无序，这里现排）
        List<MarketStock> onBoard = new ArrayList<>();
        for (MarketStock row : zt) {
            int b = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (b == n) {
                onBoard.add(row);
            }
        }
        onBoard.sort((a, b) -> {
            BigDecimal ca = a.getChangePct();
            BigDecimal cb = b.getChangePct();
            if (ca == null && cb == null) return 0;
            if (ca == null) return 1;
            if (cb == null) return -1;
            return cb.compareTo(ca);
        });

        List<TiantiVO.Row> rows = new ArrayList<>();
        List<TiantiVO.Row> success = new ArrayList<>();
        for (MarketStock row : onBoard) {
            TiantiVO.Row r = rowOf(row, n, snap, zong, zhongJun, kaWeiCode, fanBao);
            Integer prevN = prevBoard.get(row.getCode());
            r.setPromoted(prevN == null ? null : prevN == n - 1);
            rows.add(r);
            if (prevN != null && prevN == n - 1) {
                success.add(r);
            }
        }
        lvl.setRows(rows);
        lvl.setCount(rows.size());
        lvl.setSuccess(success);
        lvl.setPromotedCount(success.size());

        // 昨日 n-1 板：分母。注意 n=2 时昨日 1 板=首板，全部纳入
        List<MarketStock> prevCandidates = new ArrayList<>();
        for (MarketStock row : prevZT) {
            int b = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (b == n - 1) {
                prevCandidates.add(row);
            }
        }
        lvl.setPrevCount(prevCandidates.size());
        lvl.setPromoRate(prevCandidates.isEmpty() ? null
                : PrdMetricsService.pct(success.size(), prevCandidates.size()));

        // 失败：昨日 n-1 板，今日没出现在 n 板
        List<TiantiVO.FailedRow> failed = new ArrayList<>();
        Set<String> successCodes = new HashSet<>();
        for (TiantiVO.Row r : success) {
            successCodes.add(r.getCode());
        }
        for (MarketStock prev : prevCandidates) {
            if (successCodes.contains(prev.getCode())) {
                continue;
            }
            failed.add(failedRow(prev, n - 1, todayZtByCode.get(prev.getCode()),
                    todayZbByCode.get(prev.getCode())));
        }
        lvl.setFailed(failed);
        return lvl;
    }

    private TiantiVO.Row rowOf(MarketStock row, int n, PrdMetricsService.Snapshot snap,
                                Set<String> zong, Set<String> zhongJun, String kaWeiCode, Set<String> fanBao) {
        TiantiVO.Row r = new TiantiVO.Row();
        r.setCode(row.getCode());
        r.setName(row.getName());
        r.setIndustry(row.getIndustry());
        r.setBoard(n);
        r.setChangePct(row.getChangePct());
        r.setBreakCount(row.getBreakCount());
        r.setSealAmount(row.getSealAmount());
        r.setFirstSealTime(row.getFirstSealTime());
        r.setPattern(StockPatterns.of(row));
        r.setRole(roleOf(row, snap, zong, zhongJun, kaWeiCode, fanBao));
        return r;
    }

    private TiantiVO.FailedRow failedRow(MarketStock prev, int prevBoardN,
                                         MarketStock todayRow, MarketStock bombRow) {
        TiantiVO.FailedRow f = new TiantiVO.FailedRow();
        f.setCode(prev.getCode());
        f.setName(prev.getName());
        f.setIndustry(prev.getIndustry());
        f.setPrevBoard(prevBoardN);
        if (todayRow != null) {
            // 今日仍封住涨停但没到 n 板（如昨 2 板今天还是 2 板/退回 1 板）
            f.setTodayStatus("ZT");
            f.setChangePct(todayRow.getChangePct());
            f.setPattern(StockPatterns.of(todayRow));
        } else if (bombRow != null) {
            f.setTodayStatus("ZB");
            f.setChangePct(bombRow.getChangePct());
            f.setPullbackPct(bombRow.getPullbackPct());
        } else {
            // 今日既没涨停也没炸板：免费源没有全市场逐只行情，明细未覆盖就明说，不编涨跌幅
            f.setTodayStatus("GONE");
        }
        return f;
    }

    /** 标签优先级：总龙头 > 中军 > 卡位 > 反包 > 跟风（日内核心内其余） > 无标签。 */
    private static String roleOf(MarketStock row, PrdMetricsService.Snapshot snap,
                                 Set<String> zong, Set<String> zhongJun, String kaWeiCode, Set<String> fanBao) {
        String code = row.getCode();
        if (code != null && zong.contains(code)) {
            return "总龙头";
        }
        if (code != null && zhongJun.contains(code)) {
            return "中军";
        }
        if (code != null && code.equals(kaWeiCode)) {
            return "卡位";
        }
        if (code != null && fanBao.contains(code)) {
            return "反包";
        }
        if (snap.mainIndustry != null && snap.mainIndustry.equals(row.getIndustry())) {
            return "跟风";
        }
        return null;
    }

    private static TiantiVO.Dragon dragon(PrdMetricsService.Snapshot snap) {
        TiantiVO.Dragon d = new TiantiVO.Dragon();
        d.setAction(snap.zongLongAction == null ? "ABSENT" : snap.zongLongAction);
        d.setReason(snap.dragonReason);
        if (snap.zongLong != null) {
            d.setCode(snap.zongLong.getCode());
            d.setName(snap.zongLong.getName());
            d.setIndustry(snap.zongLong.getIndustry());
            d.setBoard(snap.zongLong.getConsecutive() == null ? 1 : snap.zongLong.getConsecutive());
            d.setChangePct(snap.zongLong.getChangePct());
            d.setPromoted(snap.zongLongPromoted);
        }
        return d;
    }

    private static Set<String> codes(List<MarketStock> rows) {
        Set<String> out = new HashSet<>();
        if (rows != null) {
            for (MarketStock row : rows) {
                if (row.getCode() != null) {
                    out.add(row.getCode());
                }
            }
        }
        return out;
    }

    private List<MarketStock> listPool(LocalDate date, String pool) {
        return marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, pool));
    }
}
