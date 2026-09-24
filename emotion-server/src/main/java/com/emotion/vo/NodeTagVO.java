package com.emotion.vo;

import lombok.Data;

import java.time.LocalDate;

/**
 * 候选票命中「节点追踪」（{@code t_node_event}）的一条记录。
 *
 * <p>一只票可能同时命中多条节点事件，也可能在同一条事件里担任多个角色
 * （既是节点票、又在 D0 候选名单里），所以这是一张列表而不是单值。
 *
 * <p>{@code kind} 是命中方式，只有三种取值，界面按它分组显示。
 *
 * <p>这是<strong>瞬态</strong>对象：不落库。节点事件的 status / lastRecalcAt 会被复算改写，
 * 落库等于把「当时的判断」冻在候选行上，而这里要回答的是「现在看它是不是节点票」。
 */
@Data
public class NodeTagVO {

    /** 它就是这条节点的「节点票」（node_stock）。 */
    public static final String KIND_NODE_STOCK = "NODE_STOCK";
    /** 它是这条节点的「锚定龙头」（anchor_stock）。 */
    public static final String KIND_ANCHOR = "ANCHOR";
    /** 它在 D0 候选名单里（d0_candidates）。 */
    public static final String KIND_D0_CAND = "D0_CAND";

    /** 命中方式：NODE_STOCK / ANCHOR / D0_CAND。 */
    private String kind;
    /** 来自哪条节点事件。 */
    private Long eventId;
    /** 该节点的 D0（断板日）。 */
    private LocalDate d0Date;
    /** 该节点当前状态（失效 / 待验证…）——节点会被复算改写，标出来才知道这是旧账还是新账。 */
    private String status;
    /** 该节点的锚定龙头，便于一眼认出是哪一段周期。 */
    private String anchorStock;
    /** 该节点的题材。 */
    private String theme;
    /** 该节点的节点票原文，例如「百大集团(600865)」。 */
    private String nodeStock;
    /** 节点票最高板。 */
    private Integer nodeStockMaxBoard;
}
