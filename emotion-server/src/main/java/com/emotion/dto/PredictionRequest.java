package com.emotion.dto;

import lombok.Data;

/**
 * 预判 / 对答案的一行，{@code PUT /api/records/predictions} 的 body 元素。
 * 两种行同表同端点，靠 {@link #kind} 分流；PLAN 用 prob + conditionText，
 * ANSWER 用 result + resultNote，另一对服务端置空。
 */
@Data
public class PredictionRequest {
    /** PLAN / ANSWER。 */
    private String kind;
    /** 路径名。跨日对答案只认名称回填，所以同一天里不能重名。 */
    private String name;
    private Integer prob;
    private String conditionText;
    /** 命中 / 落空 / 部分 / 违约。 */
    private String result;
    private String resultNote;
}
