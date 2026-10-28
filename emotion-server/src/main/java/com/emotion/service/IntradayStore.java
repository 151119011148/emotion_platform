package com.emotion.service;

import com.emotion.entity.StockAuction;
import com.emotion.entity.StockMinute;
import com.emotion.mapper.StockAuctionMapper;
import com.emotion.mapper.StockMinuteMapper;
import com.emotion.market.TencentClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 盘中数据（分钟序列／竞价定格）的落库口。
 *
 * <p>只有两件事在这里做：把解析结果拼成实体，以及按唯一键幂等写回。
 * 「该不该写」的判断留在任务里——日期对不对、过没过 09:30、名单是不是空的，
 * 那些是排班的语义，不是存储的语义。
 */
@Component
public class IntradayStore {

    private final StockMinuteMapper minuteMapper;
    private final StockAuctionMapper auctionMapper;

    public IntradayStore(StockMinuteMapper minuteMapper, StockAuctionMapper auctionMapper) {
        this.minuteMapper = minuteMapper;
        this.auctionMapper = auctionMapper;
    }

    /**
     * 写一票当天的分钟序列，返回交给自己写的行数。
     *
     * <p>一次一票一整天：240 根一条语句写完，逐根往返在慢网络下会把整趟循环拖成分钟级，
     * 而这一趟只在当天有效。
     */
    public int saveMinutes(String code, LocalDate date, List<TencentClient.MinuteTick> ticks) {
        if (code == null || date == null || ticks == null || ticks.isEmpty()) {
            return 0;
        }
        List<StockMinute> rows = new ArrayList<StockMinute>(ticks.size());
        for (TencentClient.MinuteTick tick : ticks) {
            if (tick == null || tick.getMinute() == null) {
                continue;
            }
            StockMinute row = new StockMinute();
            row.setCode(code);
            row.setTradeDate(date);
            row.setMinute(tick.getMinute());
            row.setPrice(tick.getPrice());
            row.setCumVolumeHand(tick.getCumVolumeHand());
            row.setCumAmountYuan(tick.getCumAmountYuan());
            rows.add(row);
        }
        return rows.isEmpty() ? 0 : minuteMapper.upsertBatch(rows);
    }

    /** 一批竞价快照。空集合直接回 0，不打一次空语句。 */
    public int saveAuctions(List<StockAuction> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return auctionMapper.upsertBatch(rows);
    }

    /**
     * 盘口一行 → 竞价快照一行。
     *
     * <p>{@code lastPrice} 拿不到就返回 null：那意味着这行根本不是有效报价（限流、空响应、
     * 或代码已退市），写进去就是一行「今天竞价 0 元成交」的假数据，比不写坏得多。
     * 其余字段缺就缺，照 null 落库——它们是补充信息，不构成这一行成立与否的前提。
     */
    public static StockAuction toAuction(TencentClient.StockQuote quote, LocalDate date, LocalDateTime capturedAt) {
        if (quote == null || quote.getLastPrice() == null || quote.getCode() == null) {
            return null;
        }
        StockAuction row = new StockAuction();
        row.setCode(quote.getCode());
        row.setName(quote.getName() == null ? "" : quote.getName());
        row.setTradeDate(date);
        row.setAuctionPrice(quote.getLastPrice());
        row.setPrevClose(quote.getPrevClose());
        row.setVolumeHand(quote.getVolumeHand());
        row.setAmountWan(quote.getAmountWan());
        row.setBid1Price(quote.getBid1Price());
        row.setBid1Hand(quote.getBid1Hand());
        row.setAsk1Price(quote.getAsk1Price());
        row.setAsk1Hand(quote.getAsk1Hand());
        row.setLimitUpPrice(quote.getLimitUpPrice());
        row.setCapturedAt(capturedAt);
        row.setSnapshotRaw(trim(quote.getRawPayload()));
        return row;
    }

    /** 原文列是 VARCHAR(1000)：宁可留个截断标记，也不要让一整批因为一段超长一起失败。 */
    private static String trim(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= 1000 ? raw : raw.substring(0, 997) + "...";
    }
}
