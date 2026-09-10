package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.vo.TiantiVO;

/**
 * 连板天梯（PRD P2）：当日涨停池 ≥2 板按四层分组，逐只挂龙头分工标签。
 *
 * <p>划界直接复用 {@link LadderMetricsService#hsplit}/{@link LadderMetricsService#layerIndex}
 * 的动态口径（低=2；中=3-4；中高=5..hsplit；极高=hsplit+1..H）——天梯的分层和打分的四层
 * 溢价必须能对上号，否则页面上"极高位"两边的票对不起来。空层保留（断档本身是信息）。
 *
 * <p>龙头标签取 {@link PrdMetricsService} 的同一份判定（总龙头/中军/卡位/反包给到只，
 * 跟风=主线内扣除前四者的涨停股），页面上每个标签都能在 score-detail 的阵眼维里复现。
 * 晋级旗标按昨日涨停池连板数比对（昨日同代码连板数 = 今日-1 即 true）。
 */
@Service
public class TiantiService {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

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
        Map<String, Integer> prevBoard = new HashMap<>();
        LocalDate prev = marketStockMapper.prevDetailDate(date);
        if (prev != null) {
            for (MarketStock row : listPool(prev, MarketStock.POOL_LIMIT_UP)) {
                prevBoard.put(row.getCode(), row.getConsecutive() == null ? 1 : row.getConsecutive());
            }
        }

        TiantiVO vo = new TiantiVO();
        vo.setTradeDate(date);
        vo.setMaxBoard(snap.maxBoard);
        vo.setMainIndustry(snap.mainIndustry);
        vo.setZtGatherPct(snap.ztGatherPct);
        vo.setHeightGatherPct(snap.heightGatherPct);
        vo.setPersistenceDays(snap.persistenceDays);
        vo.setZtTotal(snap.ztTotal);
        vo.setZbTotal(snap.zbTotal);
        vo.setDragon(dragon(snap));

        // 角色只按代码认（snapshot 与本服务的行是两趟查询，对象不等号但代码同源）
        Set<String> zong = new HashSet<>();
        if (snap.zongLong != null && snap.zongLong.getCode() != null) {
            zong.add(snap.zongLong.getCode());
        }
        Set<String> zhongJun = codes(snap.zhongJun);
        Set<String> fanBao = codes(snap.fanBao);
        String kaWeiCode = snap.kaWei == null ? null : snap.kaWei.getCode();

        // 梯子：连板 ≥2，先板高降序、同板高按涨幅降序
        List<MarketStock> ladder = new ArrayList<>();
        for (MarketStock row : zt) {
            int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (n >= 2) {
                ladder.add(row);
            }
        }
        ladder.sort((a, b) -> {
            int na = a.getConsecutive() == null ? 1 : a.getConsecutive();
            int nb = b.getConsecutive() == null ? 1 : b.getConsecutive();
            if (na != nb) {
                return nb - na;
            }
            BigDecimal ca = a.getChangePct();
            BigDecimal cb = b.getChangePct();
            if (ca == null && cb == null) {
                return 0;
            }
            if (ca == null) {
                return 1;
            }
            if (cb == null) {
                return -1;
            }
            return cb.compareTo(ca);
        });

        int h = Math.max(snap.maxBoard, 2);
        int split = LadderMetricsService.hsplit(h);
        List<TiantiVO.Tier> tiers = new ArrayList<>();
        addTier(tiers, tier("top", "极高位", split + 1, h, ladder, prevBoard, snap, zong, zhongJun, kaWeiCode, fanBao));
        addTier(tiers, tier("midhigh", "中高位", 5, split, ladder, prevBoard, snap, zong, zhongJun, kaWeiCode, fanBao));
        addTier(tiers, tier("mid", "中位", 3, 4, ladder, prevBoard, snap, zong, zhongJun, kaWeiCode, fanBao));
        addTier(tiers, tier("low", "低位", 2, 2, ladder, prevBoard, snap, zong, zhongJun, kaWeiCode, fanBao));
        vo.setTiers(tiers);
        return vo;
    }

    private static void addTier(List<TiantiVO.Tier> out, TiantiVO.Tier t) {
        if (t != null) {
            out.add(t);
        }
    }

    /** 区间无效（lo>hi，H 不足时中高/极高段）整层不返回，而不是返回一个不可能有票的空档。 */
    private static TiantiVO.Tier tier(String key, String label, int lo, int hi, List<MarketStock> ladder,
                                      Map<String, Integer> prevBoard, PrdMetricsService.Snapshot snap,
                                      Set<String> zong, Set<String> zhongJun, String kaWeiCode, Set<String> fanBao) {
        if (lo > hi) {
            return null;
        }
        TiantiVO.Tier t = new TiantiVO.Tier();
        t.setKey(key);
        t.setLabel(label);
        t.setBoardRange(lo + "-" + hi + "板");
        List<TiantiVO.Row> rows = new ArrayList<>();
        if (lo <= hi) {
            for (MarketStock row : ladder) {
                int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
                if (n < lo || n > hi) {
                    continue;
                }
                TiantiVO.Row r = new TiantiVO.Row();
                r.setCode(row.getCode());
                r.setName(row.getName());
                r.setIndustry(row.getIndustry());
                r.setBoard(n);
                r.setChangePct(row.getChangePct());
                r.setBreakCount(row.getBreakCount());
                r.setRole(roleOf(row, n, snap, zong, zhongJun, kaWeiCode, fanBao));
                Integer prevN = prevBoard.get(row.getCode());
                r.setPromoted(prevN == null ? null : prevN == n - 1);
                rows.add(r);
            }
        }
        t.setRows(rows);
        t.setCount(rows.size());
        return t;
    }

    /** 标签优先级：总龙头 > 中军 > 卡位 > 反包 > 跟风（主线内其余） > 无标签。 */
    private static String roleOf(MarketStock row, int board, PrdMetricsService.Snapshot snap,
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
