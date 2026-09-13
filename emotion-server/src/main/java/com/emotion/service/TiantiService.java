package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.StockPatterns;
import com.emotion.vo.TiantiVO;

/**
 * 连板生态（PRD P2）：当日涨停池按<b>板高</b>从 H 到 2 逐层组成天梯（左对齐、全宽），
 * 每层给个股（龙头分工标签 + 一字/T字/换手形态 + 封单额，同层按封单额降序）；
 * 3 板及以上层把晋级失败个股并入同层（前端灰色标注），失败去向依次查涨停/炸板/跌停池，
 * 最新交易日再用腾讯批量报价兜底"未触板"个股的当日涨跌幅。
 *
 * <p>三层动态归属（低=2/中=3-4/高=5板+）按
 * {@link LadderMetricsService#layerIndex} 打在每层上，
 * 保证天梯的层名与打分三层对得上。龙头标签与判定依据全部取自 {@link PrdMetricsService} 同一份快照。
 */
@Service
public class TiantiService {

    private static final Logger log = LoggerFactory.getLogger(TiantiService.class);

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    // 三层（2026-09-13 简化，对齐高位生态 D5）：低位=2板 / 中位=3-4板 / 高位=5板+
    private static final String[] LAYER_LABELS = {"低位", "中位", "高位"};
    /** 失败明细从 n≥3 层下探到 n≥2 层（1进2=昨首板今兑现），是低位溢价全样本的关键来源。 */
    private static final int QUOTE_FALLBACK_MIN_BOARD = 2;

    private final PrdMetricsService prdMetrics;
    private final MarketStockMapper marketStockMapper;
    private final CrossDayQuoteAugmentor crossDayQuote;
    private final ZtPerfStore ztPerfStore;
    private final ManualLeaderService manualLeaderService;

    public TiantiService(PrdMetricsService prdMetrics, MarketStockMapper marketStockMapper,
                         CrossDayQuoteAugmentor crossDayQuote, ZtPerfStore ztPerfStore,
                         ManualLeaderService manualLeaderService) {
        this.prdMetrics = prdMetrics;
        this.marketStockMapper = marketStockMapper;
        this.crossDayQuote = crossDayQuote;
        this.ztPerfStore = ztPerfStore;
        this.manualLeaderService = manualLeaderService;
    }

    public TiantiVO vo(Long userId, LocalDate requested) {
        LocalDate date = requested != null ? requested : LocalDate.now(CN);
        PrdMetricsService.Snapshot snap = prdMetrics.snapshot(userId, date);

        List<MarketStock> zt = listPool(date, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> zb = listPool(date, MarketStock.POOL_BROKEN);
        List<MarketStock> dt = listPool(date, MarketStock.POOL_LIMIT_DOWN);
        LocalDate prev = marketStockMapper.prevDetailDate(date);
        List<MarketStock> prevZT = prev == null ? new ArrayList<MarketStock>()
                : listPool(prev, MarketStock.POOL_LIMIT_UP);
        Map<String, Integer> prevBoard = new HashMap<>();
        for (MarketStock row : prevZT) {
            prevBoard.put(row.getCode(), row.getConsecutive() == null ? 1 : row.getConsecutive());
        }
        // 今日个股按代码索引（涨停 + 炸板 + 跌停），判晋级失败后的去向
        Map<String, MarketStock> todayZtByCode = indexByCode(zt);
        Map<String, MarketStock> todayZbByCode = indexByCode(zb);
        Map<String, MarketStock> todayDtByCode = indexByCode(dt);

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
        int lbTotal = 0;
        for (MarketStock row : zt) {
            int b = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (b >= 2) {
                lbTotal++;
            }
        }
        vo.setLbTotal(lbTotal);

        Set<String> zong = new HashSet<>();
        if (snap.zongLong != null && snap.zongLong.getCode() != null) {
            zong.add(snap.zongLong.getCode());
        }
        Set<String> zhongJun = codes(snap.zhongJun);
        Set<String> fanBao = codes(snap.fanBao);
        String kaWeiCode = snap.kaWei == null ? null : snap.kaWei.getCode();

        int h = Math.max(snap.maxBoard, 2);
        // 人工总龙头：某日用户手动指定的身份（与自动"空间板"并列，可同可异）
        String manualLeader = manualLeaderService.codeOf(userId, date);

        // 天梯：n 从 H 往下到 2，每层独立判晋级
        List<TiantiVO.Level> levels = new ArrayList<>();
        for (int n = h; n >= 2; n--) {
            levels.add(level(n, h, zt, prevZT, prevBoard, todayZtByCode, todayZbByCode, todayDtByCode, snap,
                    zong, zhongJun, kaWeiCode, fanBao, manualLeader));
        }
        // 最新交易日：三池都没覆盖到的失败股，用腾讯实时报价兜底当日涨跌幅（历史日快照回溯不了）
        fillGoneQuotes(date, levels);
        for (TiantiVO.Level lvl : levels) {
            sortFailed(lvl.getFailed());
        }
        vo.setLevels(levels);
        return vo;
    }

    private TiantiVO.Level level(int n, int h,
                                 List<MarketStock> zt,
                                 List<MarketStock> prevZT,
                                 Map<String, Integer> prevBoard,
                                 Map<String, MarketStock> todayZtByCode,
                                 Map<String, MarketStock> todayZbByCode,
                                 Map<String, MarketStock> todayDtByCode,
                                 PrdMetricsService.Snapshot snap,
                                 Set<String> zong, Set<String> zhongJun, String kaWeiCode, Set<String> fanBao,
                                 String manualLeader) {
        TiantiVO.Level lvl = new TiantiVO.Level();
        lvl.setBoard(n);
        lvl.setLayerLabel(LAYER_LABELS[LadderMetricsService.layerIndex(n, h)]);

        // 今日 n 板个股，同层按封单金额从大到小（null 垫后），同额取涨幅大、代码小保证确定性
        List<MarketStock> onBoard = new ArrayList<>();
        for (MarketStock row : zt) {
            int b = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (b == n) {
                onBoard.add(row);
            }
        }
        onBoard.sort((a, b) -> {
            int bySeal = Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())
                    .compare(a.getSealAmount(), b.getSealAmount());
            if (bySeal != 0) {
                return bySeal;
            }
            int byPct = Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())
                    .compare(a.getChangePct(), b.getChangePct());
            if (byPct != 0) {
                return byPct;
            }
            return Comparator.nullsLast(String::compareTo).compare(a.getCode(), b.getCode());
        });

        List<TiantiVO.Row> rows = new ArrayList<>();
        Set<String> successCodes = new HashSet<>();
        for (MarketStock row : onBoard) {
            TiantiVO.Row r = rowOf(row, n, snap, zong, zhongJun, kaWeiCode, fanBao, manualLeader);
            Integer prevN = prevBoard.get(row.getCode());
            r.setPromoted(prevN == null ? null : prevN == n - 1);
            rows.add(r);
            if (prevN != null && prevN == n - 1) {
                successCodes.add(r.getCode());
            }
        }
        lvl.setRows(rows);
        lvl.setCount(rows.size());

        // 失败：昨日 n-1 板，今日没封住 n 板。注意 n=2 时昨日 1 板=首板
        List<TiantiVO.FailedRow> failed = new ArrayList<>();
        for (MarketStock prev : prevZT) {
            int prevN = prev.getConsecutive() == null ? 1 : prev.getConsecutive();
            if (prevN != n - 1 || successCodes.contains(prev.getCode())) {
                continue;
            }
            failed.add(failedRow(prev, n - 1, todayZtByCode.get(prev.getCode()),
                    todayZbByCode.get(prev.getCode()), todayDtByCode.get(prev.getCode())));
        }
        lvl.setFailed(failed);
        return lvl;
    }

    private TiantiVO.Row rowOf(MarketStock row, int n, PrdMetricsService.Snapshot snap,
                                Set<String> zong, Set<String> zhongJun, String kaWeiCode, Set<String> fanBao,
                                String manualLeader) {
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
        r.setRole(roleOf(row, snap.mainIndustry, zong, zhongJun, kaWeiCode, fanBao));
        // 人工总龙头与自动"空间板"并列：同只可同时持有两个标签，不同只则分开标
        r.setManualLeader(manualLeader != null && manualLeader.equals(row.getCode()));
        return r;
    }

    private TiantiVO.FailedRow failedRow(MarketStock prev, int prevBoardN,
                                         MarketStock todayRow, MarketStock bombRow, MarketStock downRow) {
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
        } else if (downRow != null) {
            // 今日跌停：此前只查涨停/炸板池，这批个股被误报成"明细未覆盖"
            f.setTodayStatus("DT");
            f.setChangePct(downRow.getChangePct());
        } else {
            // 今日没进任何池：免费源没有全市场逐只行情，最新交易日再尝试腾讯报价兜底
            f.setTodayStatus("GONE");
        }
        return f;
    }

    /**
     * 最新交易日才补腾讯（库里没有更晚明细日）；先落与打分同源的当日三池 ∪ t_zt_perf（快照日批量采集），
     * 历史日只能靠这两级，最新交易日再把腾讯报价兜到底。现在 1进2（n=2 层，昨首板今未触板）也覆盖，
     * 不再出现"打分引擎有低位大面、天梯名单却标明细未覆盖"的口径分裂。
     */
    private void fillGoneQuotes(LocalDate date, List<TiantiVO.Level> levels) {
        LocalDate next;
        try {
            next = marketStockMapper.nextDetailDate(date);
        } catch (RuntimeException e) {
            next = null;
        }
        // 与打分引擎同源：当日三池（ZT/ZB/DT） ∪ t_zt_perf（T-1 涨停种子今表现全样本）
        Map<String, BigDecimal> resolved = new HashMap<>(crossDayQuote.todayPoolPct(date));
        for (Map.Entry<String, BigDecimal> e : ztPerfStore.readPctByCode(date).entrySet()) {
            resolved.putIfAbsent(e.getKey(), e.getValue());
        }
        List<TiantiVO.FailedRow> gone = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (TiantiVO.Level lvl : levels) {
            if (lvl.getBoard() < QUOTE_FALLBACK_MIN_BOARD) {
                continue;
            }
            for (TiantiVO.FailedRow f : lvl.getFailed()) {
                if ("GONE".equals(f.getTodayStatus()) && f.getChangePct() == null && f.getCode() != null) {
                    gone.add(f);
                    codes.add(f.getCode());
                }
            }
        }
        if (gone.isEmpty()) {
            return;
        }
        // 先落两库级的价格；历史交易日到此为止
        for (TiantiVO.FailedRow f : gone) {
            BigDecimal p = resolved.get(f.getCode());
            if (p != null) {
                f.setChangePct(p);
            }
        }
        if (next != null) {
            return; // 还有更晚明细日 → 腾讯快照给的不是这一天
        }
        // 最新交易日才用腾讯兜底仍未覆盖的缺口（复用打分同一条 augmentGone 路径）
        Map<String, BigDecimal> aug = crossDayQuote.augmentGone(date, codes, resolved);
        int filled = 0;
        for (TiantiVO.FailedRow f : gone) {
            if (f.getChangePct() == null) {
                BigDecimal p = aug.get(f.getCode());
                if (p != null) {
                    f.setChangePct(p);
                    filled++;
                }
            }
        }
        if (filled > 0) {
            log.info("{} 天梯晋级失败股腾讯报价补全 {}/{} 只", date, filled, gone.size());
        }
    }

    /** 失败名单按当日涨幅升序（最惨在前），取不到涨幅的垫后；同涨幅按代码保证确定性。 */
    private static void sortFailed(List<TiantiVO.FailedRow> failed) {
        if (failed == null || failed.size() <= 1) {
            return;
        }
        failed.sort((a, b) -> {
            int byPct = Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder())
                    .compare(a.getChangePct(), b.getChangePct());
            if (byPct != 0) {
                return byPct;
            }
            return Comparator.nullsLast(String::compareTo).compare(a.getCode(), b.getCode());
        });
    }

    /** 标签优先级：空间板(自动最高板) > 中军 > 卡位 > 反包 > 跟风；"总龙头"走人工 {@code manualLeader} 另标。 */
    private static String roleOf(MarketStock row, String mainIndustry, Set<String> zong, Set<String> zhongJun,
                                 String kaWeiCode, Set<String> fanBao) {
        String code = row.getCode();
        if (code != null && zong.contains(code)) {
            // zong=全市场最高连板（PrdMetricsService.zongLong）；自动标"空间板"
            return "空间板";
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
        if (mainIndustry != null && mainIndustry.equals(row.getIndustry())) {
            return "跟风";
        }
        return null;
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

    private static Map<String, MarketStock> indexByCode(List<MarketStock> rows) {
        Map<String, MarketStock> map = new LinkedHashMap<>();
        for (MarketStock row : rows) {
            if (row.getCode() != null) {
                map.putIfAbsent(row.getCode(), row);
            }
        }
        return map;
    }

    private List<MarketStock> listPool(LocalDate date, String pool) {
        return marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, pool));
    }
}
