package com.emotion.dto;

import lombok.Data;

/** 运行一次选股的请求体。 */
@Data
public class WaveRiderRunRequest {
    private Long strategyId;
    /** yyyy-MM-dd；不给就按今天。 */
    private String tradeDate;
    /** true=只算不落库（回测 / 试跑）。 */
    private Boolean dryRun;
}
