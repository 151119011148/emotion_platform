package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.dto.DailyRecordRequest;
import com.emotion.dto.MarketFields;
import com.emotion.entity.MarketDaily;
import com.emotion.mapper.MarketDailyMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * {@code t_market_daily} 的唯一写口与读口：全局客观行情日数据（不绑用户）。
 *
 * <p>写入口有三条，语义刻意不同：
 * <ul>
 *   <li>{@link #upsertSnapshot}：/snapshot 拉到的客观九数，<b>非空才 patch</b>，
 *       涨跌家数只在 liveBreadth=true（拉的就是当天实时时段）时写，历史日绝不张冠李戴；</li>
 *   <li>{@link #upsertForm}：复盘表单保存，七个数走空值守卫，涨跌家数看 JSON 键在不在场
 *       （在场发 null = 清回未填），与旧 copyFields 同一套分界；</li>
 *   <li>{@link #upsertBreadth}：复盘 md 导入的「涨跌家数」键，键写了空值就显式清空。</li>
 * </ul>
 *
 * <p>upsert 一律"先读后写整行"：MyBatis-Plus 默认 NOT_NULL 更新策略天然实现非空 patch；
 * up_count/down_count 两列是 ALWAYS，读出整行再回写不会误伤，还能表达显式清空。
 */
@Service
public class MarketDailyStore {

    private final MarketDailyMapper mapper;

    public MarketDailyStore(MarketDailyMapper mapper) {
        this.mapper = mapper;
    }

    public MarketDaily getByDate(LocalDate date) {
        if (date == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<MarketDaily>()
                .eq(MarketDaily::getTradeDate, date)
                .last("LIMIT 1"));
    }

    /** 某日之前（不含当日）最近 limit 个客观日行，按日期倒序；量能 20 日基准窗口用。 */
    public List<MarketDaily> listBefore(LocalDate date, int limit) {
        if (date == null) {
            return java.util.Collections.emptyList();
        }
        return mapper.selectList(new LambdaQueryWrapper<MarketDaily>()
                .lt(MarketDaily::getTradeDate, date)
                .orderByDesc(MarketDaily::getTradeDate)
                .last("LIMIT " + limit));
    }

    /** 批量取一批日期的客观行（列表合并用，一次查询，避免逐行打库）。 */
    public java.util.Map<LocalDate, MarketDaily> mapByDates(java.util.Collection<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        java.util.Map<LocalDate, MarketDaily> out = new java.util.HashMap<>();
        for (MarketDaily row : mapper.selectList(new LambdaQueryWrapper<MarketDaily>()
                .in(MarketDaily::getTradeDate, dates))) {
            out.put(row.getTradeDate(), row);
        }
        return out;
    }

    /**
     * /snapshot 落客观九数。非空字段才覆盖；{@code liveBreadth}=false（拉历史日）时
     * 连 up/down 两键都不碰——历史日的实时数不是那天的数。
     */
    public MarketDaily upsertSnapshot(LocalDate date, MarketFields f, boolean liveBreadth) {
        if (date == null || f == null) {
            return getByDate(date);
        }
        MarketDaily row = loadOrNew(date);
        if (f.getMaxConsecutiveLimit() != null) row.setMaxConsecutiveLimit(f.getMaxConsecutiveLimit());
        if (f.getLimitUpCount() != null) row.setLimitUpCount(f.getLimitUpCount());
        if (f.getLimitDownCount() != null) row.setLimitDownCount(f.getLimitDownCount());
        if (f.getYesterdayLimitPremium() != null) row.setYesterdayLimitPremium(f.getYesterdayLimitPremium());
        if (f.getBrokenBoardRate() != null) row.setBrokenBoardRate(f.getBrokenBoardRate());
        if (f.getBigLossCount() != null) row.setBigLossCount(f.getBigLossCount());
        if (f.getTotalVolume() != null) row.setTotalVolume(f.getTotalVolume());
        if (liveBreadth) {
            if (f.getUpCount() != null) row.setUpCount(f.getUpCount());
            if (f.getDownCount() != null) row.setDownCount(f.getDownCount());
        }
        return persist(row);
    }

    /**
     * 表单 → 客观日表。七个数空值守卫（发 null 与不发一样不动）；
     * up/down 看键在不在场：在场照发（null = 清回未填），不在场一字不动。
     */
    public MarketDaily upsertForm(LocalDate date, DailyRecordRequest req, Set<String> present) {
        if (date == null || req == null) {
            return getByDate(date);
        }
        MarketDaily row = loadOrNew(date);
        if (req.getMaxConsecutiveLimit() != null) row.setMaxConsecutiveLimit(req.getMaxConsecutiveLimit());
        if (req.getLimitUpCount() != null) row.setLimitUpCount(req.getLimitUpCount());
        if (req.getLimitDownCount() != null) row.setLimitDownCount(req.getLimitDownCount());
        if (req.getYesterdayLimitPremium() != null) row.setYesterdayLimitPremium(req.getYesterdayLimitPremium());
        if (req.getBrokenBoardRate() != null) row.setBrokenBoardRate(req.getBrokenBoardRate());
        if (req.getBigLossCount() != null) row.setBigLossCount(req.getBigLossCount());
        if (req.getTotalVolume() != null) row.setTotalVolume(req.getTotalVolume());
        if (present.contains("upCount")) row.setUpCount(req.getUpCount());
        if (present.contains("downCount")) row.setDownCount(req.getDownCount());
        return persist(row);
    }

    /** md 导入「涨跌家数」：键在场即两个数照发，空值显式置 NULL（键没写由调用方跳过本方法）。 */
    public MarketDaily upsertBreadth(LocalDate date, Integer upCount, Integer downCount) {
        MarketDaily row = loadOrNew(date);
        row.setUpCount(upCount);
        row.setDownCount(downCount);
        return persist(row);
    }

    private MarketDaily loadOrNew(LocalDate date) {
        MarketDaily row = getByDate(date);
        if (row == null) {
            row = new MarketDaily();
            row.setTradeDate(date);
        }
        return row;
    }

    private MarketDaily persist(MarketDaily row) {
        if (row.getId() == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        return row;
    }
}
