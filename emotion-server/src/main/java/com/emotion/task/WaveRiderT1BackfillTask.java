package com.emotion.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Strategy;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.StrategyMapper;
import com.emotion.mapper.TradingHolidayMapper;
import com.emotion.scheduler.ManagedTask;
import com.emotion.scheduler.TaskResult;
import com.emotion.waverider.WaveRiderEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedList;
import java.util.List;

/**
 * 每交易日 20:30 回补候选票在 D+1 的实际表现（{@code t_candidate_t1}）。
 *
 * <p><strong>为什么是晚上、而且为什么要单独一个任务。</strong>这一步原先挂在 15:30 的
 * {@code waveRiderDailyScanTask} 里当「前置步骤」，设计意图是「漏跑一天下一次自己补上」。
 * 实测它从来没补上过一行——{@code t_scheduler_run} 连续多日都是「补写 X 的 T+1 共 0 行」。
 * 原因不在排班漏跑，而在上游：补写要拿 {@code open(D+1)}，走的是腾讯个股日线，
 * 而 {@code DailyBarService} 的配置注释自己就写着「腾讯个股日线当天滞后」；
 * 15:30 当日那根还没发布，{@code upsertT1} 拿到 {@code openT1 == null} 就静默返回 false。
 * 实测当日日线要 17:48 之后才落得进 {@code t_daily_bar}。
 *
 * <p>20:30 这个点是让开 19:00 的行情拉取与 19:30 的复盘客观数据录入——三个任务打的是同一批上游，
 * 挤在一起只会互相拖慢；同时又还在同一个晚上，他做复盘之前数就齐了。
 *
 * <p><strong>为什么一次扫 3 个交易日而不是 1 个。</strong>{@code backfillT1} 是幂等 upsert，
 * 而已经结算的窗口走 {@code t_daily_bar} 库缓存（{@code volatile-days: 3} 之外不再回源），
 * 所以多扫两天几乎不打上游，却换来了「漏一个晚上，第二天自己补齐」的能力——
 * 这才是原先那句设计意图真正该有的实现方式。{@code misfire_policy} 也因此可以用 DO_NOTHING。
 *
 * <p>「补写 0 行」是本任务唯一需要警惕的回归信号：某天开始天天全 0，
 * 说明上游发布时点又往后挪了，该动的是排班时间，不是这里的逻辑。
 *
 * <p>任务在<strong>后端进程内</strong>运行：到点时后端必须开着，宿主机不能休眠。
 */
@Component("waveRiderT1BackfillTask")
public class WaveRiderT1BackfillTask implements ManagedTask {

    private static final Logger log = LoggerFactory.getLogger(WaveRiderT1BackfillTask.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /** 往回扫几个交易日。见类注释：多扫的两天走库缓存，代价近零，换来的是漏跑自愈。 */
    private static final int SWEEP_DAYS = 3;

    private final StrategyMapper strategyMapper;
    private final MarketStockMapper marketStockMapper;
    private final TradingHolidayMapper tradingHolidayMapper;
    private final WaveRiderEngine engine;

    public WaveRiderT1BackfillTask(StrategyMapper strategyMapper,
                                   MarketStockMapper marketStockMapper,
                                   TradingHolidayMapper tradingHolidayMapper,
                                   WaveRiderEngine engine) {
        this.strategyMapper = strategyMapper;
        this.marketStockMapper = marketStockMapper;
        this.tradingHolidayMapper = tradingHolidayMapper;
        this.engine = engine;
    }

    @Override
    public TaskResult run() {
        return run(LocalDate.now(CN));
    }

    /** 拆出日期参数只为可测：跑测试那天不一定是交易日。 */
    TaskResult run(LocalDate today) {
        if (!isTradingDay(today)) {
            return TaskResult.skip(today + " 非交易日（周末或休市），跳过 T+1 回补");
        }

        List<Strategy> strategies = strategyMapper.selectList(new LambdaQueryWrapper<Strategy>()
                .eq(Strategy::getEnabled, 1));
        if (strategies.isEmpty()) {
            return TaskResult.skip("没有启用中的策略，跳过 T+1 回补");
        }

        // 验证日 D+1 的行情没入库，就没有任何东西可验——照选股任务那条纪律，
        // 宁可明确 skip，也不要扫一遍报「0 行」让人以为是上游又滞后了。
        if (!engine.hasPoolData(today)) {
            return TaskResult.skip(today + " 三池数据未入库，D+1 无从验证。"
                    + "行情拉取任务跑完后可手工触发一次本任务");
        }

        List<LocalDate> days = sweepDates(today);
        if (days.isEmpty()) {
            return TaskResult.skip("行情明细里找不到 " + today + " 之前的交易日，无可回补的日子");
        }

        int patched = 0;
        StringBuilder detail = new StringBuilder();
        for (Strategy s : strategies) {
            StringBuilder per = new StringBuilder();
            for (LocalDate d : days) {
                // 逐日兜住异常：某一天的某只票取数失败不该把其余日子一起带下水，
                // 缺口留给下一个晚上——扫 3 天本来就是为了这个。
                try {
                    int n = engine.backfillT1(s.getId(), d);
                    patched += n;
                    per.append(d).append("→").append(n).append("行");
                } catch (RuntimeException e) {
                    log.warn("策略[{}]补写 {} 的 T+1 失败：{}", s.getName(), d, e.toString());
                    per.append(d).append("→失败");
                }
                per.append(' ');
            }
            detail.append(s.getName()).append('：').append(per.toString().trim()).append('；');
        }

        return TaskResult.ok(String.format("T+1 回补完成：%d 个策略 × %d 个交易日，共补写 %d 行。%s",
                strategies.size(), days.size(), patched, detail.toString()));
    }

    /**
     * 从今天往回数 {@link #SWEEP_DAYS} 个<strong>有行情明细</strong>的交易日，升序返回。
     *
     * <p>走 {@code prevDetailDate} 而不是自己减天数：周末、节假日、以及某天行情根本没拉到的情况
     * 都被它一并跳过，不需要在这里再判一次交易日历。
     *
     * <p>今天本身不在结果里——它的 D+1 还不存在，{@code backfillT1} 对它会直接返回 0，
     * 扫它只是白跑一趟。
     */
    private List<LocalDate> sweepDates(LocalDate today) {
        LinkedList<LocalDate> out = new LinkedList<>();
        LocalDate cursor = today;
        while (out.size() < SWEEP_DAYS) {
            LocalDate prev = marketStockMapper.prevDetailDate(cursor);
            if (prev == null) {
                break;
            }
            out.addFirst(prev);
            cursor = prev;
        }
        return out;
    }

    private boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        List<LocalDate> holidays = tradingHolidayMapper.listBetween(date, date);
        return holidays == null || holidays.isEmpty();
    }
}
