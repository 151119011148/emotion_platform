package com.emotion.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Stock;
import com.emotion.mapper.StockMapper;
import com.emotion.market.EastmoneyClient;
import com.emotion.market.MarketDataException;
import com.emotion.market.StockListResult;
import com.emotion.market.StockRow;
import com.emotion.vo.StockRefreshVO;

/**
 * A股代码名称总表的灌数与查询。
 *
 * 这张表只解决一件事：让"总龙头/中军"这种手填框能按名字搜到代码。
 * 它是可选增强，拉取失败不影响温度计——所以刷新走的是独立接口，不掺进 /snapshot。
 */
@Service
public class StockListService {

    private static final Logger log = LoggerFactory.getLogger(StockListService.class);
    private static final int CHUNK = 500;
    private static final int SEARCH_LIMIT = 20;
    private static final int QUERY_MAX_LENGTH = 20;

    private final EastmoneyClient eastmoney;
    private final StockMapper mapper;

    public StockListService(EastmoneyClient eastmoney, StockMapper mapper) {
        this.eastmoney = eastmoney;
        this.mapper = mapper;
    }

    public StockRefreshVO refresh() {
        StockListResult list = eastmoney.allStocks();
        if (!list.isOk()) {
            throw new MarketDataException("A股代码名单拉取失败：" + list.getReason());
        }
        if (list.getRows().isEmpty()) {
            // 空名单一次 upsert 就是"什么都没做"，但返回 200 会被当成成功——必须报出来
            throw new MarketDataException("A股代码名单为空，未写入");
        }

        List<Stock> rows = toEntities(list);
        long before = count();
        for (int from = 0; from < rows.size(); from += CHUNK) {
            mapper.upsertBatch(rows.subList(from, Math.min(from + CHUNK, rows.size())));
        }
        long after = count();

        StockRefreshVO vo = new StockRefreshVO();
        vo.setUpstreamTotal(list.getTotal());
        vo.setFetched(rows.size());
        vo.setInserted((int) Math.max(0, after - before));
        vo.setRewritten(rows.size() - vo.getInserted());
        vo.setDropped(list.getDropped());
        vo.setTruncated(list.isTruncated());
        if (list.isTruncated()) {
            vo.setNote("翻页未走完（上游总数 " + list.getTotal() + "，本次只取到 " + rows.size() + "）");
        }
        log.info("A股代码表刷新：上游 {} / 写入 {}（新增 {}），过滤 {}",
                list.getTotal(), rows.size(), vo.getInserted(), list.getDropped().size());
        return vo;
    }

    /** 代码前缀或名称模糊匹配，最多 20 条。空输入返回空列表而不是全表。 */
    public List<Stock> search(String rawQuery) {
        String trimmed = rawQuery == null ? "" : rawQuery.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        final String q = trimmed.length() > QUERY_MAX_LENGTH
                ? trimmed.substring(0, QUERY_MAX_LENGTH) : trimmed;
        return mapper.selectList(new LambdaQueryWrapper<Stock>()
                .and(w -> w.likeRight(Stock::getCode, q).or().like(Stock::getName, q))
                .orderByAsc(Stock::getCode)
                .last("LIMIT " + SEARCH_LIMIT));
    }

    private long count() {
        Long total = mapper.selectCount(null);
        return total == null ? 0L : total;
    }

    private static List<Stock> toEntities(StockListResult list) {
        List<Stock> rows = new ArrayList<>(list.getRows().size());
        for (StockRow row : list.getRows()) {
            Stock stock = new Stock();
            stock.setCode(row.getCode());
            stock.setName(row.getName());
            stock.setMarket(row.getMarket() != null ? row.getMarket() : marketOf(row.getCode()));
            stock.setBoard(boardOf(row.getCode()));
            rows.add(stock);
        }
        return rows;
    }

    /**
     * 板块按代码前缀判，不用上游的 market 字段：北交所的 market 和深市一样都是 0，
     * 拿它分板会把北交所全归成深市。
     */
    static String boardOf(String code) {
        if (code == null || code.length() != 6) {
            return "其他";
        }
        if (code.startsWith("60")) {
            return "沪主板";
        }
        if (code.startsWith("68")) {
            return "科创板";
        }
        if (code.startsWith("00")) {
            return "深主板";
        }
        if (code.startsWith("30")) {
            return "创业板";
        }
        if (code.startsWith("43") || code.startsWith("83") || code.startsWith("87")
                || code.startsWith("88") || code.startsWith("92")) {
            return "北交所";
        }
        return "其他";
    }

    private static int marketOf(String code) {
        return code != null && code.startsWith("6") ? 1 : 0;
    }
}
