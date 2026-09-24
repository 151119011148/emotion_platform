package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 内置策略模板：主升浪 / 退潮期 / 震荡市三套起步配置。
 *
 * <p>不绑 user_id——模板是系统预置的，所有人看到的一样。套用模板到某个策略 =
 * 把这里的 config_json 写成一个新版本，不产生新策略实体。
 */
@Data
@TableName("t_strategy_template")
public class StrategyTemplate {

    public static final String CODE_MAIN_UP = "MAIN_UP";
    public static final String CODE_RETREAT = "RETREAT";
    public static final String CODE_RANGE = "RANGE";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** MAIN_UP / RETREAT / RANGE。 */
    private String templateCode;
    private String templateName;
    private String configJson;
    private Integer sortNo;
}
