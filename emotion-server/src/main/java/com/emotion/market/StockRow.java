package com.emotion.market;

import lombok.Data;

/** 一只股票的两个字：代码和名称。来自东财列表接口，不含行情。 */
@Data
public class StockRow {
    private String code;
    /** 上游 f13：1=沪，0=深（北交所也报 0，所以不能用它分板块）。 */
    private Integer market;
    private String name;
}
