package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.StrategyVersion;

/** 策略版本快照。append-only，界面上的版本 diff 由 service 层比对 config_json 得出。 */
public interface StrategyVersionMapper extends BaseMapper<StrategyVersion> {
}
