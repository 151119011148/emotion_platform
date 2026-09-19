package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.ThemeSnapshot;
import com.emotion.mapper.ThemeSnapshotMapper;
import com.emotion.vo.IntradayVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 日内核心题材 Top5 快照的唯一写口：题材榜（{@code IntradayVO.ThemeRow}）按强度已排好序，
 * 取前 N 名持久化到 {@code t_theme_daily_snapshot}，先删后插（同行业快照约定）。
 * 题材维度按用户个性化，快照含 userId；当日已有快照时跳过（幂等，读取不重复刷写）。
 */
@Service
public class ThemeSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ThemeSnapshotService.class);

    /** 题材榜只持久化当日 Top N。 */
    private static final int TOP_N = 5;

    private final ThemeSnapshotMapper mapper;

    public ThemeSnapshotService(ThemeSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    /** 从已排序的题材名次里落 Top N；当日已有快照则跳过。返回写入条数；0 表示已有/为空。 */
    @Transactional(rollbackFor = Exception.class)
    public int snapshotTop5(Long userId, LocalDate date,
                            List<IntradayVO.ThemeRow> rankedRows) {
        if (userId == null || rankedRows == null || rankedRows.isEmpty()) {
            return 0;
        }
        long exists = mapper.selectCount(new LambdaQueryWrapper<ThemeSnapshot>()
                .eq(ThemeSnapshot::getUserId, userId)
                .eq(ThemeSnapshot::getTradeDate, date));
        if (exists > 0) {
            return 0;
        }
        List<ThemeSnapshot> top = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_N, rankedRows.size()); i++) {
            IntradayVO.ThemeRow r = rankedRows.get(i);
            ThemeSnapshot s = new ThemeSnapshot();
            s.setUserId(userId);
            s.setTradeDate(date);
            s.setRank(i + 1);
            s.setThemeName(r.getName());
            s.setZtCount(r.getZtCount());
            s.setStrength(BigDecimal.valueOf(r.getStrength()));
            s.setMaxBoard(r.getMaxBoard());
            s.setContinuousDays(r.getContinuousDays());
            s.setHardness(r.getHardness());
            s.setLifecycle(r.getStatus());
            if (r.getIndustries() != null && !r.getIndustries().isEmpty()) {
                s.setRelatedIndustries(String.join("+", r.getIndustries()));
            }
            if (r.getLeader() != null) {
                s.setLeaderCode(r.getLeader().getCode());
                s.setLeaderName(r.getLeader().getName());
                s.setLeaderBoard(r.getLeader().getBoard());
            }
            top.add(s);
        }
        if (top.isEmpty()) {
            return 0;
        }
        mapper.delete(new LambdaQueryWrapper<ThemeSnapshot>()
                .eq(ThemeSnapshot::getUserId, userId)
                .eq(ThemeSnapshot::getTradeDate, date));
        mapper.insertBatch(top);
        log.info("题材 Top{} 快照写入 user={} {}：{}", TOP_N, userId, date, top.size());
        return top.size();
    }

    public List<ThemeSnapshot> list(Long userId, LocalDate date) {
        return mapper.selectList(new LambdaQueryWrapper<ThemeSnapshot>()
                .eq(ThemeSnapshot::getUserId, userId)
                .eq(ThemeSnapshot::getTradeDate, date)
                .orderByAsc(ThemeSnapshot::getRank));
    }
}