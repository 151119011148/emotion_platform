package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.emotion.entity.MarketStock;
import com.emotion.vo.MainlineVO;

/**
 * 主线详情（PRD P6）：五要素、生命周期、龙头分工、轮动信号，全部出自
 * {@link PrdMetricsService} 快照——打分、天梯、主线页三处同源，不允许页面上出现
 * 第二套算出来的"主线"。
 *
 * <p>生命周期是<b>确定性规则</b>不是机器学习（实现见
 * {@link PrdMetricsService#lifecycleStage}）：退潮优先（总龙头断板或主线涨停腰斩），
 * 其余按持续性天数与全市场高度 H 递进：≥5 天或 H≥7 亢奋、≥3 扩散、≥2 确认、否则萌芽。
 * 引擎另按阶段对 D2 设分数天花板（萌芽 50 / 确认 70 / 扩散 85 / 亢奋 100 / 退潮 30）。
 *
 * <p>成交额聚集度：固定=涨停股口径（主线涨停股 amount / 全部涨停股 amount，快照 amountGatherPct），
 * 不接受旧"两市口径"人工列覆盖——同一指标只允许一个口径。
 */
@Service
public class MainlineService {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /** 与 PRD P6 轨道一致的固定段序。 */
    static final List<String> LIFECYCLE = Arrays.asList("萌芽", "确认", "扩散", "亢奋", "退潮");

    private final PrdMetricsService prdMetrics;

    public MainlineService(PrdMetricsService prdMetrics) {
        this.prdMetrics = prdMetrics;
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
        vo.setAmountGatherPct(snap.amountGatherPct);
        vo.setDragon(dragon(snap));
        vo.setSectorLeader(snap.mainLeader == null ? null : member(snap.mainLeader));
        vo.setDragonAligned(snap.mainIndustry == null ? null : Boolean.valueOf(snap.dragonAligned));
        vo.setZhongJun(members(snap.zhongJun));
        vo.setGenFengCount(snap.genFengCount);
        vo.setKaWei(kaWei(snap));
        vo.setFanBao(members(snap.fanBao));
        vo.setRotationSignals(snap.rotationSignals);
        vo.setLifecycleStage(snap.lifecycleStage);
        return vo;
    }

    private Integer hardness(PrdMetricsService.Snapshot snap) {
        BigDecimal v = snap.metrics.get("catalyst_hardness");
        return v == null ? null : Integer.valueOf(v.intValue());
    }

    private static MainlineVO.Dragon dragon(PrdMetricsService.Snapshot snap) {
        MainlineVO.Dragon d = new MainlineVO.Dragon();
        d.setAction(snap.zongLongAction == null ? "ABSENT" : snap.zongLongAction);
        d.setReason(snap.dragonReason);
        d.setPromoted(snap.zongLongPromoted);
        d.setInMainSector(snap.mainIndustry == null ? null
                : Boolean.valueOf(snap.zongLong != null && snap.mainIndustry.equals(snap.zongLong.getIndustry())));
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
            out.add(member(row));
        }
        return out;
    }

    private static MainlineVO.Member member(MarketStock row) {
        MainlineVO.Member m = new MainlineVO.Member();
        m.setCode(row.getCode());
        m.setName(row.getName());
        m.setIndustry(row.getIndustry());
        m.setBoard(row.getConsecutive() == null ? 1 : row.getConsecutive());
        m.setChangePct(row.getChangePct());
        return m;
    }

    private static MainlineVO.Member kaWei(PrdMetricsService.Snapshot snap) {
        if (snap.kaWei == null) {
            return null;
        }
        MainlineVO.Member m = member(snap.kaWei);
        m.setSealed(snap.kaWeiSealed);
        return m;
    }
}
