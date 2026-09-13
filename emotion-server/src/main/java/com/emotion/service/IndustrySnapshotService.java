package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.IndustrySnapshot;
import com.emotion.mapper.IndustrySnapshotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code t_industry_daily_snapshot} 的唯一写口（T5）：从当日涨停池现算行业板块快照并整日重建。
 *
 * <p>"先删后插"与 {@link StockPoolWriter} 同一约定：上游一次只保留最近约 15 个交易日，
 * 重算一天时不能把没充值进去的板块留成上一次的残影，否则 D2 主线分布永远对不上当日涨停池。
 * 删除与插入必须在一个事务里，中断才不会把既有快照清空。
 */
@Service
public class IndustrySnapshotService {

    private static final Logger log = LoggerFactory.getLogger(IndustrySnapshotService.class);

    private final IndustrySnapshotMapper mapper;

    public IndustrySnapshotService(IndustrySnapshotMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @return 写回的实际板块数；-1 表示当日涨停池一无所出（不写，保留上一次快照）
     */
    @Transactional(rollbackFor = Exception.class)
    public int replaceForDate(LocalDate date) {
        List<IndustrySnapshot> rows = mapper.aggregate(date);
        if (rows.isEmpty()) {
            log.info("{} 涨停池无行业可聚合，行业快照不写", date);
            return -1;
        }
        for (IndustrySnapshot row : rows) {
            row.setTradeDate(date);
        }
        mapper.delete(new LambdaQueryWrapper<IndustrySnapshot>()
                .eq(IndustrySnapshot::getTradeDate, date));
        for (int from = 0; from < rows.size(); from += 400) {
            mapper.insertBatch(rows.subList(from, Math.min(from + 400, rows.size())));
        }
        log.info("{} 行业板块快照写入 {} 个板块", date, rows.size());
        return rows.size();
    }

    public List<IndustrySnapshot> list(LocalDate date) {
        return mapper.selectList(new LambdaQueryWrapper<IndustrySnapshot>()
                .eq(IndustrySnapshot::getTradeDate, date)
                .orderByDesc(IndustrySnapshot::getZtCount)
                .orderByDesc(IndustrySnapshot::getMaxBoard));
    }
}