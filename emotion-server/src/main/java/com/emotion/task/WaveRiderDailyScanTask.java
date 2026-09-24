package com.emotion.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Strategy;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.StrategyMapper;
import com.emotion.mapper.TradingHolidayMapper;
import com.emotion.market.MarketDataException;
import com.emotion.scheduler.ManagedTask;
import com.emotion.scheduler.TaskResult;
import com.emotion.service.MarketDailyStore;
import com.emotion.service.MarketDataService;
import com.emotion.vo.MarketSnapshotVO;
import com.emotion.waverider.WaveRiderEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 每交易日 15:30 跑一遍所有启用中的策略，产出当日候选池。
 *
 * <p>执行顺序是「先补昨天的账，再算今天的票」：候选在 D+1 的表现是权重回归的唯一燃料，
 * 而它只有在 D+1 收盘后才有。把补账做成同一次运行的前置步骤，漏跑一天时下一次会自己补上，
 * 不需要人去翻「到底漏了哪天」。
 *
 * <p>两条不会写脏数据的保证：
 * <ul>
 *   <li>当日三池没有数据时返回 {@link TaskResult#skip}，而不是写一批空候选——
 *       空候选在界面上和「今天确实没票」长得一样，那是比不写更坏的结果。
 *       真拿不到就尝试现拉一次行情，再没有才放弃。</li>
 *   <li>停用的策略不跑。这是 {@code enabled} 唯一的含义。</li>
 * </ul>
 *
 * <p>任务在<strong>后端进程内</strong>运行：到点时后端必须开着，宿主机不能休眠。
 * 漏跑了不会报错，只会在 {@code t_scheduler_run} 里没有那天的行——
 * 排查时先看那里有没有记录。
 */
@Component("waveRiderDailyScanTask")
public class WaveRiderDailyScanTask implements ManagedTask {

    private static final Logger log = LoggerFactory.getLogger(WaveRiderDailyScanTask.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final StrategyMapper strategyMapper;
    private final MarketStockMapper marketStockMapper;
    private final TradingHolidayMapper tradingHolidayMapper;
    private final MarketDataService marketDataService;
    private final MarketDailyStore marketDailyStore;
    private final WaveRiderEngine engine;

    public WaveRiderDailyScanTask(StrategyMapper strategyMapper,
                                  MarketStockMapper marketStockMapper,
                                  TradingHolidayMapper tradingHolidayMapper,
                                  MarketDataService marketDataService,
                                  MarketDailyStore marketDailyStore,
                                  WaveRiderEngine engine) {
        this.strategyMapper = strategyMapper;
        this.marketStockMapper = marketStockMapper;
        this.tradingHolidayMapper = tradingHolidayMapper;
        this.marketDataService = marketDataService;
        this.marketDailyStore = marketDailyStore;
        this.engine = engine;
    }

    @Override
    public TaskResult run() {
        LocalDate today = LocalDate.now(CN);
        if (!isTradingDay(today)) {
            return TaskResult.skip(today + " 非交易日（周末或休市），跳过选股");
        }

        List<Strategy> strategies = strategyMapper.selectList(new LambdaQueryWrapper<Strategy>()
                .eq(Strategy::getEnabled, 1));
        if (strategies.isEmpty()) {
            return TaskResult.skip("没有启用中的策略，跳过（去配置页把要跑的策略打开）");
        }

        if (!engine.hasPoolData(today)) {
            // 前置检查：15:30 时东财三池通常已稳定，但没稳定就自己拉一次；
            // 拉完仍没有就 skip 等人工重跑——绝不写空候选。
            if (!ensurePoolData(today)) {
                return TaskResult.skip(today + " 三池数据未入库（已尝试现拉一次仍未取到），跳过。"
                        + "行情拉取任务跑完后，可手工运行一次策略补齐");
            }
        }

        LocalDate prev = marketStockMapper.prevDetailDate(today);
        int patched = 0;
        int scanned = 0;
        int candidates = 0;
        StringBuilder detail = new StringBuilder();

        for (Strategy s : strategies) {
            // ① 补写前一日候选的 D+1 表现
            if (prev != null) {
                try {
                    patched += engine.backfillT1(s.getId(), prev);
                } catch (RuntimeException e) {
                    log.warn("策略[{}]补写 {} 的 T+1 失败：{}", s.getName(), prev, e.toString());
                }
            }
            // ② 算当日候选
            try {
                WaveRiderEngine.Outcome o = engine.run(s.getId(), today,
                        com.emotion.entity.StrategyRun.TRIGGER_SCHEDULE, false);
                scanned++;
                candidates += o.getCandidates().size();
                detail.append(s.getName()).append("→").append(o.getCandidates().size()).append("只");
                if (!o.getWarnings().isEmpty()) {
                    detail.append("(").append(String.join("/", o.getWarnings())).append(")");
                }
                detail.append("；");
            } catch (RuntimeException e) {
                log.error("策略[{}]运行失败", s.getName(), e);
                detail.append(s.getName()).append("→失败：").append(e.getMessage()).append("；");
            }
        }

        return TaskResult.ok(String.format("%s 选股完成：%d/%d 个策略跑出 %d 只候选；补写 %s 的 T+1 共 %d 行。%s",
                today, scanned, strategies.size(), candidates,
                prev == null ? "-" : prev.toString(), patched, detail.toString()));
    }

    /**
     * 尝试现拉一次当日行情。
     *
     * <p>顺带补 {@code t_market_daily}：它的 upsert 原本挂在 controller 上，
     * 不走 HTTP 的调用必须自己补，否则数据拉了但全局九数那张表是空的。
     */
    private boolean ensurePoolData(LocalDate date) {
        try {
            MarketSnapshotVO vo = marketDataService.snapshot(date, true);
            if (vo != null && vo.getTradeDate() != null && vo.getFilled() != null) {
                boolean liveBreadth = vo.getSnapshotDate() != null
                        && vo.getSnapshotDate().equals(vo.getTradeDate())
                        && vo.getFilled().getUpCount() != null
                        && vo.getFilled().getDownCount() != null;
                marketDailyStore.upsertSnapshot(vo.getTradeDate(), vo.getFilled(), liveBreadth);
            }
        } catch (MarketDataException e) {
            log.warn("现拉行情失败：{}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("现拉行情异常：{}", e.toString());
        }
        return engine.hasPoolData(date);
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
