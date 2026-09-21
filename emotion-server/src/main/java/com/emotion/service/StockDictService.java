package com.emotion.service;

import com.emotion.entity.Stock;
import com.emotion.market.EastmoneyClient;
import com.emotion.market.StockListResult;
import com.emotion.market.StockRow;
import com.emotion.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * t_stock（A股代码名称总表）的维护。
 *
 * <p>为什么要单独一个服务：高位生态「新增阵眼」与持仓台账的选股都是远程搜索，背后只查 t_stock。
 * 这张表从来没人往里写过（EastmoneyClient.allStocks() 有实现但没有调用方），于是在新库上它就是空的——
 * 表现是「输股票名称搜不出来」，前端干干净净返回 0 条，很容易被当成搜索功能坏了。
 *
 * <p>同步走 upsert，不做清空重灌：改名（摘掉 ST 前缀那种）需要被覆盖，而清空重灌一旦中途失败，
 * 一张好表就变成半张。退市股也留着——这张表只做代码到名称的翻译，多一条不交易的比少一条命中代价小。
 */
@Service
public class StockDictService {

    private static final Logger log = LoggerFactory.getLogger(StockDictService.class);

    /** 分批写：5000+ 行一次 insert 会把 SQL 撑到包上限，也拖长事务。 */
    private static final int CHUNK = 1000;

    private final EastmoneyClient eastmoney;
    private final StockMapper stockMapper;

    public StockDictService(EastmoneyClient eastmoney, StockMapper stockMapper) {
        this.eastmoney = eastmoney;
        this.stockMapper = stockMapper;
    }

    /**
     * 从行情源拉一次全市场名单并 upsert 进 t_stock。
     *
     * @return 同步结果；{@code ok=false} 表示一行都没写进去（上游没给名单），旧字典原样保留
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult sync() {
        StockListResult list = eastmoney.allStocks();
        if (list == null || !list.isOk()) {
            String reason = list == null ? "行情源无响应" : list.getReason();
            log.warn("股票字典同步失败：{}", reason);
            return SyncResult.failed("行情源未给出股票名单：" + reason);
        }
        List<Stock> rows = new ArrayList<>(list.getRows().size());
        for (StockRow row : list.getRows()) {
            if (row.getCode() == null || row.getName() == null || row.getName().trim().isEmpty()) {
                continue;
            }
            Stock stock = new Stock();
            stock.setCode(row.getCode());
            stock.setName(row.getName().trim());
            stock.setMarket(row.getMarket());
            stock.setBoard(boardOf(row.getCode()));
            rows.add(stock);
        }
        if (rows.isEmpty()) {
            log.warn("股票字典同步失败：行情源名单为空");
            return SyncResult.failed("行情源返回的股票名单为空");
        }
        for (int from = 0; from < rows.size(); from += CHUNK) {
            stockMapper.upsertBatch(rows.subList(from, Math.min(from + CHUNK, rows.size())));
        }
        String msg = "股票字典已同步 " + rows.size() + " 只"
                + (list.isTruncated() ? "（上游翻页未走完，可能不全）" : "");
        log.info("{}；过滤掉非股票行 {}", msg, list.getDropped() == null ? 0 : list.getDropped().size());
        return SyncResult.ok(rows.size(), list.isTruncated(), msg);
    }

    /** 字典是否还没灌过数据：搜索兜底与「要不要提示先同步」都看它。 */
    public boolean isEmpty() {
        return stockMapper.selectCount(null) == null || stockMapper.selectCount(null) == 0L;
    }

    /**
     * 按代码前缀归板块。东财 f13 只有沪深（北交所也报 0），分不出板块，只能看代码。
     * 与 EastmoneyClient 的股票白名单同一套前缀。
     */
    static String boardOf(String code) {
        if (code == null || code.length() != 6) {
            return null;
        }
        String prefix = code.substring(0, 2);
        switch (prefix) {
            case "60":
                return "沪主板";
            case "68":
                return "科创板";
            case "00":
                return "深主板";
            case "30":
                return "创业板";
            case "43":
            case "83":
            case "87":
            case "88":
            case "92":
                return "北交所";
            default:
                return null;
        }
    }

    /** 一次同步的结果，给端点和任务留痕直接展示。 */
    public static class SyncResult {

        private boolean ok;
        private int rows;
        private boolean truncated;
        private String message;

        public static SyncResult ok(int rows, boolean truncated, String message) {
            SyncResult r = new SyncResult();
            r.ok = true;
            r.rows = rows;
            r.truncated = truncated;
            r.message = message;
            return r;
        }

        public static SyncResult failed(String message) {
            SyncResult r = new SyncResult();
            r.ok = false;
            r.message = message;
            return r;
        }

        public boolean isOk() {
            return ok;
        }

        public int getRows() {
            return rows;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public String getMessage() {
            return message;
        }
    }
}
