package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 节点识别结果：自动识别与人工标记共用一张表。
 *
 * <p>为什么不复用 {@link NodeEvent}（t_node_event）：那张表是人工登记的「阵眼」，
 * 数据结构围绕龙头个股组织；这里是策略的周期定位，围绕交易日组织，两者维度不同。
 * PRD §16 Q4 把「要不要打通」列为待决问题，当前实现是两套独立数据，互不写入。
 *
 * <p>人工标记（source=MANUAL）的 strategy_id 可以是 NULL——交易员可以只标一个「今天是切换日」，
 * 不需要先建策略。
 */
@Data
@TableName("t_node_detect")
public class NodeDetect {

    public static final String TYPE_START = "START";
    public static final String TYPE_DIVERGE = "DIVERGE";
    public static final String TYPE_SWITCH = "SWITCH";
    /** 空间破局日 = 空间板断板日 = 观察日（不产票）。V34 引入。 */
    public static final String TYPE_SPACE_BREAK = "SPACE_BREAK";
    /** 破局次日 = 修复日 = 唯一出手日。 */
    public static final String TYPE_SPACE_BREAK_NEXT = "SPACE_BREAK_NEXT";

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** NULL=人工标记，不属于任何策略。 */
    private Long strategyId;
    private LocalDate tradeDate;
    private String nodeType;
    /** 命中的规则标识，人工标记时为 NULL。 */
    private String ruleId;
    /** 命中的表达式与实测数值。事后翻旧账时，「凭什么说这天是节点」全靠它。 */
    private String hitExpr;
    /** 当日 market.* 快照，让复算不必再依赖外部数据源。 */
    private String metricsJson;
    private String source;
    /** 1=人工确认，-1=人工取消，0=未表态。AUTO 行默认 0。 */
    private Integer confirmed;
    private Long confirmedBy;
    private LocalDateTime createdAt;
}
