package com.emotion.vo;

import lombok.Data;

/**
 * 全市场涨跌家数（东财指数扩展字段 f104/f105/f106，沪深两市合计）。
 *
 * <p>上游只有实时值、不支持历史日期回溯，所以本对象永远是「当前时刻」口径；
 * 历史交易日的涨跌家数走复盘 md 导入的 t_daily_record.up_count/down_count。
 */
@Data
public class MarketBreadthVO {

    /** 上涨家数（沪 + 深）。 */
    private Integer upCount;
    /** 下跌家数（沪 + 深）。 */
    private Integer downCount;
    /** 平盘家数（沪 + 深）。 */
    private Integer flatCount;
    /** 红盘率 % = 上涨 ÷（涨+跌），分母 0 时 null。 */
    private Double redRatioPct;
}
