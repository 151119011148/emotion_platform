package com.emotion.task;

import com.emotion.dto.MarketFields;
import com.emotion.mapper.TradingHolidayMapper;
import com.emotion.market.MarketDataException;
import com.emotion.scheduler.ManagedTask;
import com.emotion.scheduler.TaskResult;
import com.emotion.service.MarketDailyStore;
import com.emotion.service.MarketDataService;
import com.emotion.vo.MarketSnapshotVO;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 每天收盘后拉一趟当天的公开行情并落库。
 *
 * <p>为什么是 pull 而不是 push：上游没有推送，也没有能回调的地址；19:00 跑在这里意味着
 * 「排班在库里、执行在进程内」，少一层外部脚本就少一个环境差异导致的"今天怎么没跑"。
 *
 * <p>一次 snapshot(today, true) 连带写进去的表：
 * t_market_stock 三池明细、t_premium_tier 档位溢价、t_zt_perf 昨日涨停今日表现、
 * t_index_close 五大指数收盘，再加这里补写的 t_market_daily 全局客观九数。
 */
@Component("dailyMarketPullTask")
public class DailyMarketPullTask implements ManagedTask {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final MarketDataService marketDataService;
    private final MarketDailyStore marketDailyStore;
    private final TradingHolidayMapper tradingHolidayMapper;

    public DailyMarketPullTask(MarketDataService marketDataService,
                               MarketDailyStore marketDailyStore,
                               TradingHolidayMapper tradingHolidayMapper) {
        this.marketDataService = marketDataService;
        this.marketDailyStore = marketDailyStore;
        this.tradingHolidayMapper = tradingHolidayMapper;
    }

    @Override
    public TaskResult run() {
        LocalDate today = LocalDate.now(CN);
        if (!isTradingDay(today)) {
            // 周末/法定休市：不是故障，别记成失败，否则留痕里的红条全是噪音
            return TaskResult.skip(today + " 非交易日（周末或休市），跳过拉取");
        }
        MarketSnapshotVO vo;
        try {
            vo = marketDataService.snapshot(today, true);
        } catch (MarketDataException e) {
            return TaskResult.fail("行情拉取失败：" + e.getMessage());
        }
        persistObjectiveDaily(vo);
        return TaskResult.ok(summary(vo));
    }

    /**
     * t_market_daily 的写入原本挂在 MarketController 里，定时任务不走 HTTP，得自己补上这一步。
     * 判据与 controller 保持一致：只有「拉的就是行情源当前交易时段」时，实时的涨跌家数才可信，
     * 历史日的实时数不代表那天，那两列不碰。
     */
    private void persistObjectiveDaily(MarketSnapshotVO vo) {
        if (vo.getTradeDate() == null || vo.getFilled() == null) {
            return;
        }
        boolean liveBreadth = vo.getSnapshotDate() != null
                && vo.getSnapshotDate().equals(vo.getTradeDate())
                && vo.getFilled().getUpCount() != null
                && vo.getFilled().getDownCount() != null;
        marketDailyStore.upsertSnapshot(vo.getTradeDate(), vo.getFilled(), liveBreadth);
    }

    private boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        List<LocalDate> holidays = tradingHolidayMapper.listBetween(date, date);
        return holidays == null || holidays.isEmpty();
    }

    private String summary(MarketSnapshotVO vo) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(Objects.toString(vo.getTradeDate(), "-")).append(" 行情已拉取并落库");
        MarketFields f = vo.getFilled();
        if (f != null) {
            sb.append("：涨停 ").append(Objects.toString(f.getLimitUpCount(), "-"))
                    .append(" 家 / 跌停 ").append(Objects.toString(f.getLimitDownCount(), "-"))
                    .append(" 家 / 最高连板 ").append(Objects.toString(f.getMaxConsecutiveLimit(), "-"))
                    .append(" / 两市成交 ").append(Objects.toString(f.getTotalVolume(), "-")).append(" 亿");
        }
        if (vo.getWarnings() != null && !vo.getWarnings().isEmpty()) {
            sb.append("；上游告警：").append(String.join("、", vo.getWarnings()));
        }
        return sb.toString();
    }
}
