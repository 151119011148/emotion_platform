package com.emotion.task;

import com.emotion.entity.Anchor;
import com.emotion.entity.StockAuction;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.PositionMapper;
import com.emotion.mapper.TradingHolidayMapper;
import com.emotion.market.TencentClient;
import com.emotion.scheduler.ManagedTask;
import com.emotion.scheduler.TaskResult;
import com.emotion.service.AnchorService;
import com.emotion.service.IntradayStore;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 每交易日 09:26 抓一次集合竞价定格，写 {@code t_stock_auction}。
 *
 * <p><strong>为什么必须精确到分钟，而且为什么宁可不补。</strong>竞价量在腾讯快照里不是一个独立字段，
 * 它是「09:30 之前那一刻的累计成交量」。集合竞价 09:25 出结果，09:25–09:29 这五分钟里
 * 累计成交量还只有竞价一笔、五档还挂着竞价的未匹配量，所以这时抓到的才是竞价定格；
 * 09:30 一过，连续竞价的量开始往里加，同一列的含义就换了。
 * 因此本任务在 09:25 前和 09:30 后都直接 skip：晚跑一趟写进去的是「竞价量」标签下的开盘后成交量，
 * 那是假数据，不是缺数据。{@code misfire_policy} 也因此是 DO_NOTHING——它补不了，别假装能。
 *
 * <p><strong>名单。</strong>昨日涨停池∪炸板池（今天的晋级/断板要看它的竞价）＋ 在位阵眼 ＋ 最近持仓。
 * 一次批量请求全拿：{@link TencentClient#quotes} 支持一行代码带上百只，这是它存在的理由，
 * 逐只请求会把「09:26 起五分钟内」的窗口赌在上游限流上。
 *
 * <p>字段含义我是按 2026-10-28 那行 88 段实测对出来的，09:26 当刻的真实语义没有验过，
 * 所以整行原文一并落在 {@code snapshot_raw}：判读错了还能凭原文重算，不用重抓一个已经过去的早晨。
 */
@Component("auctionSnapshotTask")
public class AuctionSnapshotTask implements ManagedTask {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /** 竞价结果 09:25 出，之前的累计量还是 0。 */
    static final LocalTime WINDOW_START = LocalTime.of(9, 25);
    /** 09:30 起连续竞价进场，这一列不再是竞价量。 */
    static final LocalTime WINDOW_END = LocalTime.of(9, 30);

    private final TencentClient tencentClient;
    private final IntradayStore intradayStore;
    private final MarketStockMapper marketStockMapper;
    private final PositionMapper positionMapper;
    private final AnchorService anchorService;
    private final TradingHolidayMapper tradingHolidayMapper;

    public AuctionSnapshotTask(TencentClient tencentClient,
                               IntradayStore intradayStore,
                               MarketStockMapper marketStockMapper,
                               PositionMapper positionMapper,
                               AnchorService anchorService,
                               TradingHolidayMapper tradingHolidayMapper) {
        this.tencentClient = tencentClient;
        this.intradayStore = intradayStore;
        this.marketStockMapper = marketStockMapper;
        this.positionMapper = positionMapper;
        this.anchorService = anchorService;
        this.tradingHolidayMapper = tradingHolidayMapper;
    }

    @Override
    public TaskResult run() {
        LocalDateTime now = LocalDateTime.now(CN);
        return run(now.toLocalDate(), now.toLocalTime());
    }

    /** 日期与时点都由外面交进来只为可测：测试那天既可能不是交易日，更不可能正好是 09:26。 */
    TaskResult run(LocalDate today, LocalTime now) {
        if (!isTradingDay(today)) {
            return TaskResult.skip(today + " 非交易日（周末或休市），无竞价可抓");
        }
        if (now.isBefore(WINDOW_START)) {
            return TaskResult.skip("现在 " + now + "，集合竞价还没出结果（09:25），此刻累计量为 0，不写");
        }
        if (!now.isBefore(WINDOW_END)) {
            return TaskResult.skip("现在 " + now + "，已开盘，累计成交量里混了连续竞价的量，"
                    + "再抓就不是竞价定格了。这一天不补");
        }

        List<String> codes = roster(today);
        if (codes.isEmpty()) {
            return TaskResult.skip(today + " 没有名单：昨日三池无明细、无在位阵眼、无持仓，不写竞价快照");
        }

        List<String> symbols = new ArrayList<String>(codes.size());
        for (String code : codes) {
            String symbol = TencentClient.symbolOf(code);
            if (symbol != null) {
                symbols.add(symbol);
            }
        }

        LocalDateTime capturedAt = today.atTime(now);
        List<StockAuction> rows = new ArrayList<StockAuction>();
        for (TencentClient.StockQuote quote : tencentClient.quotes(symbols).values()) {
            StockAuction row = IntradayStore.toAuction(quote, today, capturedAt);
            if (row != null) {
                rows.add(row);
            }
        }

        // 整批空 = 上游这次根本没给数（限流或抽风）。这时候写 0 行、报 skip 都行，
        // 但只有 fail 会让他早上看见红条——开盘前那一眼没读到竞价，正是这条链子唯一要防的。
        if (rows.isEmpty()) {
            return TaskResult.fail("竞价快照整批没取到：名单 " + codes.size()
                    + " 只，腾讯盘口一条有效报价都没有，未写库");
        }

        int saved = intradayStore.saveAuctions(rows);
        int missing = codes.size() - rows.size();
        return TaskResult.ok(String.format("%s 竞价快照已落库 %d 只（名单 %d 只%s）",
                today, saved, codes.size(), missing > 0 ? "，缺 " + missing + " 只" : ""));
    }

    /** 昨日两池 ＋ 在位阵眼 ＋ 最近持仓，去重。 */
    private List<String> roster(LocalDate today) {
        Set<String> codes = new LinkedHashSet<String>();
        LocalDate prev = marketStockMapper.prevDetailDate(today);
        if (prev != null) {
            codes.addAll(nullSafe(marketStockMapper.listHotCodes(prev)));
        }
        for (Anchor anchor : anchorService.listInPosition(today)) {
            if (anchor != null && anchor.getStockCode() != null) {
                codes.add(anchor.getStockCode());
            }
        }
        codes.addAll(nullSafe(positionMapper.listHeldCodesLatest()));
        codes.remove(null);
        codes.remove("");
        return new ArrayList<String>(codes);
    }

    private static List<String> nullSafe(List<String> list) {
        return list == null ? new ArrayList<String>() : list;
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
