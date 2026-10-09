package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.DailyRecord;

/** 一个交易日全局一行（uk_date），所以没有任何按账号取数的自定义查询。 */
public interface DailyRecordMapper extends BaseMapper<DailyRecord> {
}
