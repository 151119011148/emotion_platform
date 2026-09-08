package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.PremiumTier;
import com.emotion.mapper.PremiumTierMapper;
import com.emotion.market.MarketMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code t_premium_tier} 的唯一出入口：写走「先删后插」，读还原成 {@link MarketMetrics.PremiumTiers}。
 *
 * 写为什么不只叫 Writer：它同时是重算和卡片 tooltip 的读路径，一个只承诺写的类被三处拿去查库，
 * 早晚会有人在里面偷偷加一层缓存。
 *
 * 用"先删后插"而不是 upsert（同 {@link StockPoolWriter}）：重拉一天时上游池子可能已经改过，
 * upsert 会把上一次多出来的档位永久留在库里，而"6 板档还剩两只"和"6 板档一只都没有"
 * 是两个会直接改变打分的结论。
 */
@Service
public class PremiumTierStore {

    private static final Logger log = LoggerFactory.getLogger(PremiumTierStore.class);

    private final PremiumTierMapper mapper;

    public PremiumTierStore(PremiumTierMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 一日一次重写。
     *
     * @param tiers 当日分组结果；null 或一个档都没有意味着"这次没算出来"，
     *              此时保留上一次的数据而不是清空——清空会让那一天从"有档位溢价"退化成"没录"
     * @return 实际写入行数，-1 表示跳过
     */
    @Transactional(rollbackFor = Exception.class)
    public int replaceForDate(LocalDate date, MarketMetrics.PremiumTiers tiers) {
        if (date == null || tiers == null || tiers.getTiers().isEmpty()) {
            log.info("{} 无档位溢价可写，保留上一次数据", date);
            return -1;
        }
        List<PremiumTier> rows = new ArrayList<>();
        for (MarketMetrics.TierPremium tier : tiers.getTiers()) {
            PremiumTier row = new PremiumTier();
            row.setTradeDate(date);
            row.setBoard(tier.getBoard());
            row.setGroupKey(tiers.groupOf(tier).name());
            row.setStockCount(tier.getStockCount());
            row.setMatched(tier.getMatched());
            row.setAvgPct(tier.getAvgPct());
            row.setMaxPct(tier.getMaxPct());
            row.setMinPct(tier.getMinPct());
            rows.add(row);
        }
        mapper.delete(new LambdaQueryWrapper<PremiumTier>().eq(PremiumTier::getTradeDate, date));
        mapper.insertBatch(rows);
        log.info("{} 档位溢价写入 {} 档（覆盖 {}/{} 只）",
                date, rows.size(), tiers.getMatched(), tiers.getConsidered());
        return rows.size();
    }

    /** 读一日的逐档行，还原成分组溢价对象供打分与卡片使用。空表返回 null，让调用方走"未评"。 */
    public MarketMetrics.PremiumTiers read(LocalDate date) {
        if (date == null) {
            return null;
        }
        List<PremiumTier> rows = mapper.selectList(
                new LambdaQueryWrapper<PremiumTier>().eq(PremiumTier::getTradeDate, date));
        if (rows.isEmpty()) {
            return null;
        }
        List<MarketMetrics.TierPremium> tiers = new ArrayList<>();
        for (PremiumTier row : rows) {
            tiers.add(MarketMetrics.TierPremium.ofStored(row.getBoard(), nz(row.getStockCount()),
                    nz(row.getMatched()), row.getAvgPct(), row.getMaxPct(), row.getMinPct()));
        }
        return MarketMetrics.tiersFromStored(tiers);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
