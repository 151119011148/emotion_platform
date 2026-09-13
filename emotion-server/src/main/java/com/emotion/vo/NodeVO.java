package com.emotion.vo;

import java.time.LocalDate;
import java.util.List;

import com.emotion.entity.NodeEvent;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 节点追踪页的富化视图：在 {@link NodeEvent} 全部落库字段之上，
 * 叠加只在读的时候才算得出的实时信息——阵眼与角色、节点票今天还活不活、监管小红标、T+1 自动判定。
 *
 * <p>落库列 vs 派生列分开是有意的：阵眼名/角色、节点票实时状态、监管标记、复算建议这几样
 * 如果存库，行情一变就发霉，必须读时现算。只有锚定龙头 {@code anchor_id}（血缘）、题材、
 * D0 情绪分、上次复算时间才是节点自己该持有的快照，存库。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NodeVO extends NodeEvent {

    // ---- 阵眼血缘（由 anchor_id join t_anchor 得出；anchor_id 为 NULL 时为空手填）----
    /** 关联阵眼的 6 位代码（监管小红标与节点票存活判断都会用到）。 */
    private String anchorCode;
    /** 关联阵眼的名称（t_anchor.stock_name），显示用。 */
    private String anchorName;
    /** 关联阵眼的角色码（ZONG/FENZHI/BUZHANG/FANBAO/...）。 */
    private String anchorRole;
    /** 关联阵眼的中文角色（总龙头/分支龙/补涨龙/反包龙/...）。 */
    private String anchorRoleLabel;
    /** 阵眼跨度起点。 */
    private LocalDate anchorStartDate;
    /** 阵眼跨度终点，NULL=仍在位。 */
    private LocalDate anchorEndDate;

    // ---- 节点票今日存活（读时现算，不入库）----
    /** 节点票最近一次明细里的连板数；不在涨停池则为 0。null = 还没确认节点票。 */
    private Integer nodeStockBoard;
    /** 节点票当前状态的友好文案：在梯·N板 / 断板(不在梯) / 未确认节点票 / 无明细。 */
    private String nodeStockStatus;

    // ---- 监管小红标（读时现算，只标 SEVERE/EXCH 且窗口内）----
    /** 节点票或锚定龙头在监管期内。 */
    private Boolean surveillance;
    /** 监管说明：谁、哪类、公告日。 */
    private String surveillanceDesc;

    // ---- T+1 自动判定（仅 getCurrent 填充；list 里为空）----
    /** 平台按盘面自动复算的建议；已 ready 时后端已把结论写进 status_note 并记为"待采纳"。 */
    private NodeSuggestVO suggestion;
    /** 生效节点的细分标签（读时拼）：主因(有效·强/反包失效…) + 叠加监管 + 叠加情绪退潮。显示用，不入库。 */
    private List<String> causeTags;
}