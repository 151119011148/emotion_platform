package com.emotion.task;

import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.TradingHolidayMapper;
import com.emotion.market.PoolCounts;
import com.emotion.scheduler.ManagedTask;
import com.emotion.scheduler.TaskResult;
import com.emotion.service.DailyRecordService;
import com.emotion.service.ReviewFetchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 每交易日 19:30 把复盘页那两步（一键拉取行情 → 保存复盘）里的<b>客观部分</b>做掉。
 *
 * <p>写什么：T1–T7 的公开原始数据（三池明细/五大指数/全市场统计/行业快照/档位溢价/监管名单），
 * 以及当天那一行 {@code t_daily_record}——只有客观九数与引擎算出来的派生列
 * （五维分/温度/阶段带/方向/信号）。一个交易日全局一行，当天已有行就只重算派生列。
 *
 * <p><b>绝不写什么</b>：十三项主观字段（主线、龙头、龙二、轮动笔记、复盘笔记、明日计划、
 * 我的仓位、题材评分、九个 {@code manual_*} 覆盖列）与持仓台账 {@code t_position}。
 * 那些归他人工维护；机器代填等于把「没判断」伪装成「判断过了」，而这两者在页面上长得一样。
 *
 * <p>为什么是 19:30：19:00 那趟 {@code daily_market_pull} 打的是同一个上游快照，错开半小时
 * 避免两次拉取叠在一起，也让三池先落好——本任务第 ② 步要用它当建行的前置闸。
 *
 * <p>两道「宁可不写」的闸（与 {@link WaveRiderDailyScanTask} 同一条口径）：编排整体失败、
 * 或当日三池明细为空时，<b>不建记录行</b>。一行只有空客观数的记录，在页面上和
 * 「这天什么都没发生」无法区分，那比不写更坏。
 */
@Component("reviewObjectiveTask")
public class ReviewObjectiveTask implements ManagedTask {

    private static final Logger log = LoggerFactory.getLogger(ReviewObjectiveTask.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final TradingHolidayMapper tradingHolidayMapper;
    private final MarketStockMapper marketStockMapper;
    private final ReviewFetchService reviewFetchService;
    private final DailyRecordService dailyRecordService;

    public ReviewObjectiveTask(TradingHolidayMapper tradingHolidayMapper,
                               MarketStockMapper marketStockMapper,
                               ReviewFetchService reviewFetchService,
                               DailyRecordService dailyRecordService) {
        this.tradingHolidayMapper = tradingHolidayMapper;
        this.marketStockMapper = marketStockMapper;
        this.reviewFetchService = reviewFetchService;
        this.dailyRecordService = dailyRecordService;
    }

    @Override
    public TaskResult run() {
        return run(LocalDate.now(CN));
    }

    /** 拆出日期参数只为可测：跑测试那天不一定是交易日。 */
    TaskResult run(LocalDate today) {
        if (!isTradingDay(today)) {
            return TaskResult.skip(today + " 非交易日（周末或休市），跳过复盘客观数据录入");
        }

        // ① T1–T7 客观原始数据落库，与复盘页「一键拉取行情」是同一条编排。
        //    编排里的 T8 会顺手重算当天已有的那一行复盘记录；
        //    第 ② 步会再跑一次（幂等），为省这一次去按事件文案分支不值得。
        List<ReviewFetchService.Event> events = new ArrayList<>();
        try {
            reviewFetchService.runFetch(today, events::add);
        } catch (RuntimeException e) {
            log.error("{} 复盘客观数据编排异常", today, e);
            return TaskResult.fail(today + " 拉取编排异常，未建复盘记录行：" + e.getMessage());
        }

        String done = statusOf(events, "done");
        int ready = countStatus(events, "ok");
        int warns = countStatus(events, "warn");
        int fails = countStatus(events, "fail");
        if (!"ok".equals(done) && !"warn".equals(done)) {
            return TaskResult.fail(String.format(
                    "%s 拉取编排未就绪（结论 %s，就绪 %d 项 / 警告 %d 项 / 失败 %d 项），不建复盘记录行"
                            + "——原始数据没落地，建出来就是个空壳。修好后手工再跑一次本任务即可",
                    today, Objects.toString(done, "无"), ready, warns, fails));
        }

        PoolCounts pools = marketStockMapper.countPools(today);
        if (pools == null || pools.isEmpty()) {
            return TaskResult.skip(String.format(
                    "%s 三池明细为空（上游没回数），不建复盘记录行。就绪 %d 项 / 警告 %d 项，"
                            + "拿到数据后手工再跑一次本任务即可", today, ready, warns));
        }

        // ② 补/重算当天那一行：一个交易日全局一行，只写客观数据，主观十三项与持仓不碰。
        boolean created;
        try {
            created = dailyRecordService.ensureObjectiveRecord(today);
        } catch (RuntimeException e) {
            log.error("{} 客观记录行写入失败", today, e);
            return TaskResult.fail(String.format(
                    "%s 客观数据已就绪（涨停 %d 家），但当天的复盘记录行写入失败：%s",
                    today, pools.getZtCount(), e.getMessage()));
        }

        return TaskResult.ok(String.format(
                "%s 客观数据已录入：就绪 %d 项 / 警告 %d 项 / 失败 %d 项，涨停 %d 家；"
                        + "复盘记录 %s（主观十三项与持仓未动）",
                today, ready, warns, fails, pools.getZtCount(),
                created ? "当天新建一行" : "当天已有那一行，只重算派生列"));
    }

    private static String statusOf(List<ReviewFetchService.Event> events, String task) {
        for (ReviewFetchService.Event e : events) {
            if (task.equals(e.task)) {
                return e.status;
            }
        }
        return null;
    }

    /** 数各项任务的终结状态：running 是起手式、done 是汇总，都不算某一项的就绪度。 */
    private static int countStatus(List<ReviewFetchService.Event> events, String status) {
        int n = 0;
        for (ReviewFetchService.Event e : events) {
            if (status.equals(e.status) && !"done".equals(e.task)) {
                n++;
            }
        }
        return n;
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
