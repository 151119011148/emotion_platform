package com.emotion.dto;

import lombok.Data;

/**
 * 人工标记节点的请求体。
 *
 * <p>人工标记不要求先建策略：{@code strategyId} 可以不给，此时落一行
 * {@code t_node_detect(source='MANUAL', strategy_id=NULL)}——交易员可以只想标一个
 * 「今天是切换日」，不该被逼着先去配一套策略。
 */
@Data
public class WaveRiderNodeRequest {
    private String tradeDate;
    /** START / DIVERGE / SWITCH / SPACE_BREAK / SPACE_BREAK_NEXT。 */
    private String nodeType;
    private Long strategyId;
    /** 人写的判断依据，落到 hit_expr。 */
    private String note;
}
