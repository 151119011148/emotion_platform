package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.PoolResult;
import com.emotion.market.PoolRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把三个池的逐只明细落到 {@code t_market_stock}，供仪表盘 hover 展示真实名单。
 *
 * 用"先删后插"而不是 upsert：重拉一天时上游可能已经改过池子，upsert 会把上一次多出来的
 * 股票永久留在库里，而名单和当日家数一旦对不上，这块面板就再也没人敢信。
 * 代价是删除必须和插入在同一个事务里，否则中间失败就把已有明细清了个空。
 */
@Service
public class StockPoolWriter {

    private static final Logger log = LoggerFactory.getLogger(StockPoolWriter.class);
    /** 一次几百行：太大顶到 max_allowed_packet，太小就退化成逐条往返。 */
    private static final int CHUNK = 400;
    /** 上游价格按 ×1000 传输，落库还原成元。 */
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private final MarketStockMapper mapper;

    public StockPoolWriter(MarketStockMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param bigLossRows 炸板池里判定为大面的那些行——传命中行而不是一个数，
     *                    保证落库的 big_loss 标记和当日大面家数出自同一次筛选
     * @return 实际写入行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int replaceForDate(LocalDate date,
                              PoolResult limitUp, PoolResult limitDown, PoolResult broken,
                              List<PoolRow> bigLossRows) {
        // 三个池必须都取到才重写。少一个还照删，就会把上一次完整的明细换成残缺的一份，
        // 而残缺的那份看起来和完整的一模一样。
        if (limitUp == null || !limitUp.isOk()
                || limitDown == null || !limitDown.isOk()
                || broken == null || !broken.isOk()) {
            log.info("{} 三个池未全部取到，跳过明细重写以保留上一次完整数据", date);
            return -1;
        }

        Set<String> bigLossCodes = new HashSet<>();
        for (PoolRow row : bigLossRows) {
            bigLossCodes.add(row.getCode());
        }

        List<MarketStock> rows = new ArrayList<>();
        collect(rows, date, MarketStock.POOL_LIMIT_UP, limitUp, bigLossCodes);
        collect(rows, date, MarketStock.POOL_LIMIT_DOWN, limitDown, bigLossCodes);
        collect(rows, date, MarketStock.POOL_BROKEN, broken, bigLossCodes);

        mapper.delete(new LambdaQueryWrapper<MarketStock>().eq(MarketStock::getTradeDate, date));
        for (int from = 0; from < rows.size(); from += CHUNK) {
            mapper.insertBatch(rows.subList(from, Math.min(from + CHUNK, rows.size())));
        }
        log.info("{} 盘面明细写入 {} 行（涨停 {} / 跌停 {} / 炸板 {}，其中大面 {} 只）",
                date, rows.size(), limitUp.getRows().size(), limitDown.getRows().size(),
                broken.getRows().size(), bigLossCodes.size());
        return rows.size();
    }

    private static void collect(List<MarketStock> out, LocalDate date, String pool,
                                PoolResult result, Set<String> bigLossCodes) {
        for (PoolRow row : result.getRows()) {
            if (row.getCode() == null || row.getCode().isEmpty()) {
                continue;
            }
            MarketStock stock = new MarketStock();
            stock.setTradeDate(date);
            stock.setCode(row.getCode());
            stock.setName(row.getName() == null ? "" : row.getName());
            stock.setPool(pool);
            stock.setMarket(row.getMarket());
            stock.setIndustry(row.getIndustry());
            stock.setConsecutive(row.getLbc());
            stock.setBreakCount(row.getZbc());
            stock.setChangePct(round2(row.getZdp()));
            stock.setClosePrice(yuan(row.getPrice()));
            stock.setLimitPrice(yuan(row.getLimitPrice()));
            // 成交额三池都返回；形态三字段只有涨停池有意义（fund/fbt/lbt 是涨停池专属返回）
            stock.setAmount(row.getAmount());
            if (MarketStock.POOL_LIMIT_UP.equals(pool)) {
                stock.setSealAmount(row.getFund());
                stock.setFirstSealTime(row.getFbt());
                stock.setLastSealTime(row.getLbt());
            }
            boolean isBroken = MarketStock.POOL_BROKEN.equals(pool);
            stock.setPullbackPct(isBroken ? round2(row.pullbackFromLimitPct()) : null);
            stock.setBigLoss(isBroken && bigLossCodes.contains(row.getCode()) ? 1 : 0);
            out.add(stock);
        }
    }

    private static BigDecimal yuan(BigDecimal rawThousand) {
        return rawThousand == null ? null
                : rawThousand.divide(THOUSAND, 3, RoundingMode.HALF_UP);
    }

    private static BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
