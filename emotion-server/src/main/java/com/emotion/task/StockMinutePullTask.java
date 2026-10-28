package com.emotion.task;

import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.TradingHolidayMapper;
import com.emotion.market.TencentClient;
import com.emotion.scheduler.ManagedTask;
import com.emotion.scheduler.TaskResult;
import com.emotion.service.IntradayStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 每交易日 19:12 抓当天涨停池∪炸板池的分钟序列，写 {@code t_stock_minute}。
 *
 * <p><strong>这一趟是不可逆的。</strong>腾讯分钟接口只给当天（实测 2026-10-28 请求返回
 * {@code date=20261028}，267 根，首根 0930、末根 1530），没有历史参数。也就是说
 * <b>漏跑一晚，那一天的分时在这台机器上就永久不存在了</b>——它不像 T+1 那样能靠第二天回补自愈，
 * 所以本任务只扫今天，不做「往回扫三天」那种自愈（往回扫只会拿到今天，白跑）。
 *
 * <p><strong>19:12 这个点。</strong>名单来自 {@code t_market_stock} 当天三池明细，那批数据是
 * 19:00 的 {@code dailyMarketPullTask} 写的，所以必须排在它之后；而 19:30 的
 * {@code reviewObjectiveTask} 又要打同一批上游，得排在它之前。19:12 是这两条线之间唯一的缝。
 *
 * <p><strong>逐只而不是批量。</strong>分钟接口不接多代码，一票一次请求，几十次串行往返。
 * 因此单只失败只点名、不中断：一次网络抽风丢一只票的分时，比整趟停摆丢掉全部要好，
 * 而报文里那只票的名字就是第二天该手工补谁（补不了当天，但至少知道缺的是谁）。
 */
@Component("stockMinutePullTask")
public class StockMinutePullTask implements ManagedTask {

    private static final Logger log = LoggerFactory.getLogger(StockMinutePullTask.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final TencentClient tencentClient;
    private final IntradayStore intradayStore;
    private final MarketStockMapper marketStockMapper;
    private final TradingHolidayMapper tradingHolidayMapper;

    public StockMinutePullTask(TencentClient tencentClient,
                               IntradayStore intradayStore,
                               MarketStockMapper marketStockMapper,
                               TradingHolidayMapper tradingHolidayMapper) {
        this.tencentClient = tencentClient;
        this.intradayStore = intradayStore;
        this.marketStockMapper = marketStockMapper;
        this.tradingHolidayMapper = tradingHolidayMapper;
    }

    @Override
    public TaskResult run() {
        return run(LocalDate.now(CN));
    }

    /** 拆出日期只为可测：跑测试那天未必是交易日，更未必有当天的三池明细。 */
    TaskResult run(LocalDate today) {
        if (!isTradingDay(today)) {
            return TaskResult.skip(today + " 非交易日（周末或休市），无分钟序列可抓");
        }

        List<String> codes = marketStockMapper.listHotCodes(today);
        if (codes == null || codes.isEmpty()) {
            // 19:00 那趟还没落库（在跑、崩了、或那天根本没开市）。这时候抓到的名单是空的，
            // 而分钟接口只给当天——等下一趟就是明天，这一天就永久没了，所以报 fail 而不是 skip。
            return TaskResult.fail(today + " 三池明细未入库，无法确定名单。"
                    + "分钟接口只有当天，19:00 行情任务补跑后必须立刻手工触发本任务，否则这一天永久缺失");
        }

        int stocks = 0;
        int rows = 0;
        List<String> missing = new ArrayList<String>();
        for (String code : codes) {
            String symbol = TencentClient.symbolOf(code);
            if (symbol == null) {
                missing.add(code + "(代码不成6位)");
                continue;
            }
            try {
                TencentClient.MinuteSeries series = tencentClient.minuteSeries(symbol);
                if (series.isEmpty()) {
                    missing.add(code);
                    continue;
                }
                // 上游给了日期就必须对得上：拿到的是昨天的序列还照今天落库，
                // 等于把两天的分时叠成一条假曲线，那比缺一只有害。
                if (series.getDate() != null && !today.equals(series.getDate())) {
                    missing.add(code + "(序列日期" + series.getDate() + ")");
                    log.warn("分钟序列日期不符 symbol={} 期望={} 实得={}", symbol, today, series.getDate());
                    continue;
                }
                int written = intradayStore.saveMinutes(code, today, series.getTicks());
                if (written == 0) {
                    missing.add(code);
                    continue;
                }
                stocks++;
                rows += written;
            } catch (RuntimeException e) {
                // 一只票的异常不该把剩下的几十只一起带走——这一趟过了今晚就没有第二次机会，
                // 能救回几只算几只，缺的在报文里点名。
                log.warn("分钟序列拉取失败 symbol={} 原因={}", symbol, e.toString());
                missing.add(code + "(异常)");
            }
        }

        if (stocks == 0) {
            return TaskResult.fail(String.format("%s 分钟序列全部没取到：名单 %d 只，一只都没落库。上游这一次不可用",
                    today, codes.size()));
        }
        return TaskResult.ok(String.format("%s 分钟序列已落库：%d 只 / %d 行（名单 %d 只%s）",
                today, stocks, rows, codes.size(),
                missing.isEmpty() ? "" : "，缺 " + missing.size() + " 只：" + String.join("、", missing)));
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
