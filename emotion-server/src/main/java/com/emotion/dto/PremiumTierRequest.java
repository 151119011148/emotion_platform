package com.emotion.dto;

import java.math.BigDecimal;
import java.util.Map;

import lombok.Data;

/**
 * 一日"昨涨停股今日涨跌幅"的回补入参：脚本只交"哪天"和"每只票那天涨了多少"，
 * 归档、脏值守卫、加权、落库全在服务端做——两份打分口径早晚会算出两个数。
 *
 * <p>两个回补入口共用这一个入参：{@code POST /api/market/premium-tiers}（连板档位，不收首板）
 * 与 {@code POST /api/market/zt-perf}（逐只表现，含首板）。后者用 {@link #source} 标来路。
 */
@Data
public class PremiumTierRequest {

    private java.time.LocalDate tradeDate;
    /** 6 位代码 → 当日涨跌幅%。取不到价的代码直接不出现，服务端会把它算进"未覆盖"。 */
    private Map<String, BigDecimal> pct;
    /** 仅 zt-perf 用：KBAR=日 K 回补（默认）/ BK=东财昨涨停板块 / QUOTE=批量快照。 */
    private String source;
}
