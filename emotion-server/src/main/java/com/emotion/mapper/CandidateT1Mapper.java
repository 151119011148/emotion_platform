package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.CandidateT1;

/**
 * 候选票 D+1 表现。
 *
 * <p>不写自定义 SQL：补写逻辑需要「按 (strategy_id, trade_date, code) 存在则更新、否则插入」，
 * 这用 MyBatis-Plus 的 selectOne + updateById 表达更清楚，也避免 MySQL 方言的
 * ON DUPLICATE KEY UPDATE 与本项目「派生值不落库」的约定混在一起。
 */
public interface CandidateT1Mapper extends BaseMapper<CandidateT1> {
}
