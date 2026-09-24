package com.emotion.dto;

import lombok.Data;

/** 新建策略的请求体。 */
@Data
public class WaveRiderCreateRequest {
    private String name;
    private String description;
    /** 可选：初始套用哪个模板（MAIN_UP / RETREAT / RANGE），不给就按震荡市。 */
    private String templateCode;
}
