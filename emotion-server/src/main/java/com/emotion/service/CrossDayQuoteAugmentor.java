package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.TencentClient;

/**
 * 跨日逐只报价补全（T-1 涨停种子 → T 日涨跌幅），供连板低位溢价/大面与天梯失败名单共用，
 * 保证"打分引擎"和"页面名单"用的是同一份逐只价，不再出现页面有价、打分漏数的口径分裂。
 *
 * <p>三级来源，优先级从高到低：① 当日三池（ZT/ZB/DT）行内 change_pct；
 * ② t_zt_perf（快照日批量采集的昨涨停今表现）；③ 最新交易日腾讯批量报价（历史日无法回溯，跳过）。
 * 任何一级缺失都不编数，只返回能确认的价格。
 */
@Component
public class CrossDayQuoteAugmentor {

    private static final Logger log = LoggerFactory.getLogger(CrossDayQuoteAugmentor.class);

    private final MarketStockMapper marketStockMapper;
    private final TencentClient tencent;

    public CrossDayQuoteAugmentor(MarketStockMapper marketStockMapper, TencentClient tencent) {
        this.marketStockMapper = marketStockMapper;
        this.tencent = tencent;
    }

    /** 当日三池（ZT/ZB/DT）code → 收盘涨跌幅%。任一池读取失败按已有部分返回。 */
    public Map<String, BigDecimal> todayPoolPct(LocalDate date) {
        Map<String, BigDecimal> pct = new HashMap<>();
        putPoolPct(pct, date, MarketStock.POOL_LIMIT_UP);
        putPoolPct(pct, date, MarketStock.POOL_BROKEN);
        putPoolPct(pct, date, MarketStock.POOL_LIMIT_DOWN);
        return pct;
    }

    private void putPoolPct(Map<String, BigDecimal> pct, LocalDate date, String pool) {
        try {
            for (MarketStock row : marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                    .eq(MarketStock::getTradeDate, date)
                    .eq(MarketStock::getPool, pool))) {
                if (row.getCode() != null && row.getChangePct() != null) {
                    pct.putIfAbsent(row.getCode(), row.getChangePct());
                }
            }
        } catch (RuntimeException e) {
            log.warn("读取 {} 池涨跌幅失败 date={}", pool, date, e);
        }
    }

    /**
     * 在 base（通常=三池价，已 putIfAbsent 了 t_zt_perf）之上，为仍无价的种子 code 补腾讯报价。
     * 仅最新交易日（无下一明细日）补；历史日快照无法回溯，返回原 map。
     */
    public Map<String, BigDecimal> augmentGone(LocalDate date, Collection<String> seedCodes,
                                               Map<String, BigDecimal> base) {
        Map<String, BigDecimal> out = new HashMap<>(base);
        LocalDate next;
        try {
            next = marketStockMapper.nextDetailDate(date);
        } catch (RuntimeException e) {
            next = null;
        }
        if (next != null) {
            return out; // 历史交易日：腾讯只有最新一天快照，不回溯
        }
        List<String> missing = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String code : seedCodes) {
            if (code != null && !out.containsKey(code) && seen.add(code)) {
                missing.add(code);
            }
        }
        if (missing.isEmpty()) {
            return out;
        }
        try {
            Map<String, TencentClient.StockQuote> quotes = tencent.quotes(toGtimgCodes(missing));
            int filled = 0;
            for (String code : missing) {
                TencentClient.StockQuote q = quotes.get(TencentClient.symbolOf(code));
                if (q != null && q.getChangePct() != null) {
                    out.put(code, q.getChangePct());
                    filled++;
                }
            }
            log.info("跨日报价腾讯补全 date={} 需补{}只 补到{}只", date, missing.size(), filled);
        } catch (RuntimeException e) {
            log.info("跨日报价腾讯兜底失败 date={} 原因={}", date, e.toString());
        }
        return out;
    }

    private List<String> toGtimgCodes(List<String> codes) {
        List<String> out = new ArrayList<>();
        for (String code : codes) {
            out.add(TencentClient.symbolOf(code));
        }
        return out;
    }
}
