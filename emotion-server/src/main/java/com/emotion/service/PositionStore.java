package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.emotion.entity.Position;
import com.emotion.entity.PositionHistory;
import com.emotion.mapper.PositionHistoryMapper;
import com.emotion.mapper.PositionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 持仓与纪律台账的读写。复盘 md 一天几条 {@code 持仓:} 就落几行。
 *
 * <p>叫 Store 不叫 Writer 是因为读它的不止导入器自己（复盘页明细、以后的连续违约统计）。
 * 先删后插而不是 upsert：重导一天时上一次多出来的那只票必须消失，否则"这天已经清仓了"
 * 会一直挂在台账上，而这条记录存在的意义就是提醒你别赖着不走。
 *
 * <p>整表替换前会把当天旧行<b>快照</b>进 {@code t_position_history}（见 {@link #replaceForDate}），
 * 手滑保存也能回滚——这就是"整表替换"这个原语的风险补偿。
 */
@Service
public class PositionStore {

    private static final Logger log = LoggerFactory.getLogger(PositionStore.class);
    private static final int CHUNK = 400;

    private final PositionMapper mapper;
    private final PositionHistoryMapper historyMapper;

    public PositionStore(PositionMapper mapper, PositionHistoryMapper historyMapper) {
        this.mapper = mapper;
        this.historyMapper = historyMapper;
    }

    /**
     * @param rows null = 这次没说持仓，整块跳过（返回 -1）；空表 = 明确要说"这天没持仓"，删干净。
     */
    @Transactional(rollbackFor = Exception.class)
    public int replaceForDate(Long userId, LocalDate date, List<Position> rows) {
        if (rows == null) {
            return -1;
        }
        snapshot(userId, date);
        mapper.delete(new LambdaQueryWrapper<Position>()
                .eq(Position::getUserId, userId)
                .eq(Position::getTradeDate, date));
        for (int from = 0; from < rows.size(); from += CHUNK) {
            mapper.insertBatch(rows.subList(from, Math.min(from + CHUNK, rows.size())));
        }
        log.info("{} 持仓台账写入 {} 行（已快照旧行）", date, rows.size());
        return rows.size();
    }

    /** 把当天全部旧行快照进 t_position_history：DELETE 前先存下落盘，误操作可回滚。 */
    private void snapshot(Long userId, LocalDate date) {
        List<Position> old = read(userId, date);
        if (old.isEmpty()) {
            return;
        }
        List<PositionHistory> snap = new ArrayList<>();
        for (Position p : old) {
            PositionHistory h = new PositionHistory();
            h.setUserId(userId);
            h.setTradeDate(date);
            h.setSnapshotDate(LocalDate.now());
            h.setPositionId(p.getId());
            h.setStockCode(p.getStockCode());
            h.setStockName(p.getStockName());
            h.setCostPrice(p.getCostPrice());
            h.setCurrentPrice(p.getCurrentPrice());
            h.setFloatPct(p.getFloatPct());
            h.setAction(p.getAction());
            h.setPlannedAction(p.getPlannedAction());
            h.setDiscipline(p.getDiscipline());
            h.setIndustry(p.getIndustry());
            h.setBoardNum(p.getBoardNum());
            h.setStatus(p.getStatus());
            h.setDelayDays(p.getDelayDays());
            h.setDisciplineScore(p.getDisciplineScore());
            h.setNextDayPlan(p.getNextDayPlan());
            h.setPlanOpen(p.getPlanOpen());
            h.setPlanBreak(p.getPlanBreak());
            h.setPlanLow(p.getPlanLow());
            h.setPlanFall(p.getPlanFall());
            snap.add(h);
        }
        for (int from = 0; from < snap.size(); from += CHUNK) {
            historyMapper.insertBatch(snap.subList(from, Math.min(from + CHUNK, snap.size())));
        }
        log.info("{} 持仓快照 {} 行", date, snap.size());
    }

    public List<Position> read(Long userId, LocalDate date) {
        return mapper.selectList(new LambdaQueryWrapper<Position>()
                .eq(Position::getUserId, userId)
                .eq(Position::getTradeDate, date)
                .orderByAsc(Position::getId));
    }

    /** 最近一条"待裁决"持仓：executed=0 且填了次日决策，按日期降序取最早开放的待办。 */
    public Position latestPending(Long userId, LocalDate beforeOrEqual) {
        return mapper.selectOne(new LambdaQueryWrapper<Position>()
                .eq(Position::getUserId, userId)
                .eq(Position::getExecuted, 0)
                .ne(Position::getStatus, "今日清仓")
                .le(Position::getTradeDate, beforeOrEqual)
                .orderByDesc(Position::getTradeDate)
                .orderByAsc(Position::getId)
                .last("LIMIT 1"));
    }

    /** 最近 N 条待裁决持仓（外溢到仪表盘列表 / 次日页顶部），按日期降序。 */
    public List<Position> pendingList(Long userId, LocalDate beforeOrEqual, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<Position>()
                .eq(Position::getUserId, userId)
                .eq(Position::getExecuted, 0)
                .ne(Position::getStatus, "今日清仓")
                .le(Position::getTradeDate, beforeOrEqual)
                .orderByDesc(Position::getTradeDate)
                .orderByAsc(Position::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /** 标记某持仓已执行：回填真实动作，并把 executed 置 1，闭环完成。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean markExecuted(Long userId, Long positionId, String actualAction) {
        return mapper.update(null, new LambdaUpdateWrapper<Position>()
                .eq(Position::getId, positionId)
                .eq(Position::getUserId, userId)
                .set(Position::getExecuted, 1)
                .set(Position::getActualAction, actualAction)) > 0;
    }
}