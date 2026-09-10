package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * 连板天梯（PRD P2）：当日涨停池 ≥2 板的连板股按四层分组，逐只挂龙头分工标签
 * （🔴总龙头 🔵中军 🟢跟风 🟡卡位 🟣反包）。标签由 {@link com.emotion.service.PrdMetricsService}
 * 的龙头分工判定推导，与打分引擎用的是同一份结论。
 */
@Data
public class TiantiVO {

    private LocalDate tradeDate;
    /** 全市场最高连板 H。 */
    private Integer maxBoard;
    /** 主线行业（涨停聚集度最高者）。 */
    private String mainIndustry;
    /** 主线 5 要素速览：涨停聚集度 %。 */
    private Double ztGatherPct;
    /** 高度聚集度 %。 */
    private Double heightGatherPct;
    /** 主线连续活跃天数。 */
    private Integer persistenceDays;
    private Integer ztTotal;
    private Integer zbTotal;

    /** 总龙头状态卡：即使只有首板（梯子空）也照常给出。 */
    private Dragon dragon;

    /** 四层分组，顺序极高→中高→中→低；当日该层无股则 rows 空但档位保留（断档本身就是信息）。 */
    private List<Tier> tiers;

    @Data
    public static class Dragon {
        private String code;
        private String name;
        private String industry;
        private Integer board;
        private String action;      // PROMOTE / HOLD / BREAK / ABSENT
        private Boolean promoted;
        private BigDecimal changePct;
    }

    @Data
    public static class Tier {
        private String key;         // top / midhigh / mid / low
        private String label;       // 极高位 / 中高位 / 中位 / 低位
        private String boardRange;  // 如 "5-6板"、"2板"
        private Integer count;
        private List<Row> rows;
    }

    @Data
    public static class Row {
        private String code;
        private String name;
        private String industry;
        /** 连板数。 */
        private Integer board;
        private BigDecimal changePct;
        /** 日内开板次数。 */
        private Integer breakCount;
        /** 龙头分工标签：总龙头/中军/跟风/卡位/反包；普通股=null。 */
        private String role;
        /** 昨日同代码连板数=今日-1 即 true；昨日无明细=null。 */
        private Boolean promoted;
    }
}
