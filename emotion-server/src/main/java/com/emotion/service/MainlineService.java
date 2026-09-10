package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.DailyRecordMapper;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.vo.MainlineVO;

/**
 * 主线详情（PRD P6）：五要素、生命周期、龙头分工、轮动信号，全部出自
 * {@link PrdMetricsService} 快照——打分、天梯、主线页三处同源，不允许页面上出现
 * 第二套算出来的"主线"。
 *
 * <p>生命周期是<b>确定性规则</b>不是机器学习：退潮优先（总龙头断板或主线涨停腰斩），
 * 其余按持续性天数与全市场高度 H 递进：≥5 天或 H≥7 亢奋、≥3 扩散、≥2 确认、否则萌芽。
 * 成交额聚集度只有人工口径（t_daily_record.manual_amount_gather_pct），未填=null。
 */
@Service
public class MainlineService {

    private static final Logger log = LoggerFactory.getLogger(MainlineService.class);

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /** 与 PRD P6 轨道一致的固定段序。 */
    static final List<String> LIFECYCLE = Arrays.asList("萌芽", "确认", "扩散", "亢奋", "退潮");

    private final PrdMetricsService prdMetrics;
    private final MarketStockMapper marketStockMapper;
    private final DailyRecordMapper dailyRecordMapper;

    public MainlineService(PrdMetricsService prdMetrics,
                           MarketStockMapper marketStockMapper,
                           DailyRecordMapper dailyRecordMapper) {
        this.prdMetrics = prdMetrics;
        this.marketStockMapper = marketStockMapper;
        this.dailyRecordMapper = dailyRecordMapper;
    }

    public MainlineVO vo(Long userId, LocalDate requested) {
        LocalDate date = requested != null ? requested : LocalDate.now(CN);
        PrdMetricsService.Snapshot snap = prdMetrics.snapshot(userId, date);

        MainlineVO vo = new MainlineVO();
        vo.setTradeDate(date);
        vo.setLifecycle(LIFECYCLE);
        vo.setMainIndustry(snap.mainIndustry);
        vo.setMainlineConfirmed(snap.mainlineConfirmed);
        vo.setZtGatherPct(snap.ztGatherPct);
        vo.setHeightGatherPct(snap.heightGatherPct);
        vo.setPersistenceDays(snap.persistenceDays);
        vo.setMainZt(snap.mainZt);
        vo.setZtTotal(snap.ztTotal);
        vo.setMainMaxBoard(snap.mainMaxBoard);
        vo.setMaxBoard(snap.maxBoard);
        vo.setMainThemeMatched(snap.mainTheme != null);
        vo.setCatalystHardness(hardness(snap));
        vo.setAmountGatherPct(amountGather(userId, date));
        vo.setDragon(dragon(snap));
        vo.setZhongJun(members(snap.zhongJun));
        vo.setGenFengCount(snap.genFengCount);
        vo.setKaWei(kaWei(snap));
        vo.setFanBao(members(snap.fanBao));
        vo.setRotationSignals(snap.rotationSignals);
        vo.setLifecycleStage(lifecycle(snap, prevMainZt(snap, date)));
        return vo;
    }

    /**
     * 退潮优先：总龙头断板（BREAK）或主线涨停腰斩（今日 ≤ 昨日一半）→ 退潮；
     * 否则持续性 ≥5 天或全市场 H≥7 → 亢奋；≥3 扩散；≥2 确认；其余萌芽。无主线 → null。
     */
    static String lifecycle(PrdMetricsService.Snapshot snap, int prevMainZt) {
        if (snap.mainIndustry == null) {
            return null;
        }
        boolean ebb = "BREAK".equals(snap.zongLongAction)
                || (prevMainZt > 0 && snap.mainZt * 2 <= prevMainZt);
        if (ebb) {
            return "退潮";
        }
        int days = snap.persistenceDays == null ? 0 : snap.persistenceDays;
        if (days >= 5 || snap.maxBoard >= 7) {
            return "亢奋";
        }
        if (days >= 3) {
            return "扩散";
        }
        if (days >= 2) {
            return "确认";
        }
        return "萌芽";
    }

    /** 昨日主线行业涨停家数（腰斩判定用）；昨日无明细=0（不触发腰斩）。 */
    private int prevMainZt(PrdMetricsService.Snapshot snap, LocalDate date) {
        if (snap.mainIndustry == null) {
            return 0;
        }
        try {
            return countMainZt(marketStockMapper.prevDetailDate(date), snap.mainIndustry);
        } catch (RuntimeException e) {
            log.warn("昨日主线家数读取失败 date={} 原因={}（腰斩判定退化为不触发）", date, e.toString());
            return 0;
        }
    }

    private int countMainZt(LocalDate date, String main) {
        if (date == null) {
            return 0;
        }
        int n = 0;
        for (MarketStock row : marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP))) {
            if (main.equals(row.getIndustry())) {
                n++;
            }
        }
        return n;
    }

    private Integer hardness(PrdMetricsService.Snapshot snap) {
        BigDecimal v = snap.metrics.get("catalyst_hardness");
        return v == null ? null : Integer.valueOf(v.intValue());
    }

    private Double amountGather(Long userId, LocalDate date) {
        try {
            DailyRecord record = dailyRecordMapper.selectOne(new LambdaQueryWrapper<DailyRecord>()
                    .eq(DailyRecord::getUserId, userId)
                    .eq(DailyRecord::getTradeDate, date)
                    .last("LIMIT 1"));
            return record == null || record.getManualAmountGatherPct() == null
                    ? null : record.getManualAmountGatherPct().doubleValue();
        } catch (RuntimeException e) {
            log.warn("当日记录读取失败 user={} date={} 原因={}（成交额聚集度未填）", userId, date, e.toString());
            return null;
        }
    }

    private static MainlineVO.Dragon dragon(PrdMetricsService.Snapshot snap) {
        MainlineVO.Dragon d = new MainlineVO.Dragon();
        d.setAction(snap.zongLongAction == null ? "ABSENT" : snap.zongLongAction);
        d.setReason(snap.dragonReason);
        d.setPromoted(snap.zongLongPromoted);
        if (snap.zongLong != null) {
            d.setCode(snap.zongLong.getCode());
            d.setName(snap.zongLong.getName());
            d.setIndustry(snap.zongLong.getIndustry());
            d.setBoard(snap.zongLong.getConsecutive() == null ? 1 : snap.zongLong.getConsecutive());
            d.setChangePct(snap.zongLong.getChangePct());
        }
        return d;
    }

    private static List<MainlineVO.Member> members(List<MarketStock> rows) {
        List<MainlineVO.Member> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (MarketStock row : rows) {
            MainlineVO.Member m = new MainlineVO.Member();
            m.setCode(row.getCode());
            m.setName(row.getName());
            m.setIndustry(row.getIndustry());
            m.setBoard(row.getConsecutive() == null ? 1 : row.getConsecutive());
            m.setChangePct(row.getChangePct());
            out.add(m);
        }
        return out;
    }

    private static MainlineVO.Member kaWei(PrdMetricsService.Snapshot snap) {
        if (snap.kaWei == null) {
            return null;
        }
        MainlineVO.Member m = new MainlineVO.Member();
        m.setCode(snap.kaWei.getCode());
        m.setName(snap.kaWei.getName());
        m.setIndustry(snap.kaWei.getIndustry());
        m.setBoard(snap.kaWei.getConsecutive() == null ? 1 : snap.kaWei.getConsecutive());
        m.setChangePct(snap.kaWei.getChangePct());
        m.setSealed(snap.kaWeiSealed);
        return m;
    }
}
