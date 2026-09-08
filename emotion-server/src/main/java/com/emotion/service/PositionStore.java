package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Position;
import com.emotion.mapper.PositionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 持仓与纪律台账的读写。复盘 md 一天几条 {@code 持仓:} 就落几行。
 *
 * <p>叫 Store 不叫 Writer 是因为读它的不止导入器自己（复盘页明细、以后的连续违约统计）。
 * 先删后插而不是 upsert：重导一天时上一次多出来的那只票必须消失，否则"这天已经清仓了"
 * 会一直挂在台账上，而这条记录存在的意义就是提醒你别赖着不走。
 */
@Service
public class PositionStore {

    private static final Logger log = LoggerFactory.getLogger(PositionStore.class);
    private static final int CHUNK = 400;

    private final PositionMapper mapper;

    public PositionStore(PositionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param rows null = 这次没说持仓，整块跳过（返回 -1）；空表 = 明确要说"这天没持仓"，删干净。
     */
    @Transactional(rollbackFor = Exception.class)
    public int replaceForDate(Long userId, LocalDate date, List<Position> rows) {
        if (rows == null) {
            return -1;
        }
        mapper.delete(new LambdaQueryWrapper<Position>()
                .eq(Position::getUserId, userId)
                .eq(Position::getTradeDate, date));
        for (int from = 0; from < rows.size(); from += CHUNK) {
            mapper.insertBatch(rows.subList(from, Math.min(from + CHUNK, rows.size())));
        }
        log.info("{} 持仓台账写入 {} 行", date, rows.size());
        return rows.size();
    }

    public List<Position> read(Long userId, LocalDate date) {
        return mapper.selectList(new LambdaQueryWrapper<Position>()
                .eq(Position::getUserId, userId)
                .eq(Position::getTradeDate, date)
                .orderByAsc(Position::getId));
    }
}
