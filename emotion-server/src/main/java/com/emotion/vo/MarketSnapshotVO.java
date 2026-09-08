package com.emotion.vo;

import java.time.LocalDate;
import java.util.List;

import com.emotion.dto.MarketFields;

import lombok.Data;

/** 一次行情拉取的完整结果：填了什么、缺什么、以及每个数字是怎么来的。 */
@Data
public class MarketSnapshotVO {

    private LocalDate tradeDate;
    /** 溢价是"昨日涨停股今天的表现"，所以必须说明"昨日"到底是哪天。 */
    private LocalDate prevTradeDate;
    /** 请求的日期就是当前快照日且尚未收盘。 */
    private boolean live;
    private boolean fromCache;
    private LocalDate snapshotDate;

    private MarketFields filled;
    /** filled 里没出现的字段名，前端据此提示"仍需手工"。 */
    private List<String> missing;
    /**
     * 五大指数里有几只带着收盘价（从库里数，不是这次取了几只）。
     * 刻意不进 {@code filled}/{@code missing}：那七个是打分口径，指数收盘一维都不参与。
     */
    private Integer indexFilled;
    private Integer indexTotal;
    /** 昨日涨停池按连板档拆开的溢价：进分的是它，不是 filled.yesterdayLimitPremium。 */
    private PremiumTiersVO premiumTiers;
    /** 每个数字的取数明细，让使用者能核对而不是被迫相信。 */
    private List<String> notes;
    private List<String> warnings;
    /** 结构上就无法自动取到、必须人工判断的字段。 */
    private List<String> manualFields;
}
