package com.emotion.dto;

import java.math.BigDecimal;
import java.util.Map;

import lombok.Data;

/**
 * 一日档位溢价的回补入参：脚本只交"哪天"和"每只票那天涨了多少"，
 * 归档、脏值守卫、加权、落库全在服务端做——两份打分口径早晚会算出两个数。
 */
@Data
public class PremiumTierRequest {

    private java.time.LocalDate tradeDate;
    /** 6 位代码 → 当日涨跌幅%。取不到价的代码直接不出现，服务端会把它算进"未覆盖"。 */
    private Map<String, BigDecimal> pct;
}
