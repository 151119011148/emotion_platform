package com.emotion.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * 候选票所属的一条「通达信题材」。
 *
 * <p>题材来自 {@code t_stock_concept}——来源是 {@code infoharbor_block.dat}（通达信概念板块成分），
 * 由 V33 导入。它与引擎内部用来分组取身位的行业（{@code t_market_stock.industry}，东财口径、
 * 且被截断成 4 个汉字）<strong>不是一回事</strong>：行业是引擎的选股分组键，题材是给人看的标签。
 *
 * <p>{@code ztCount} 是「当日该题材的涨停家数」，用来把最热的那条排到前面——一只涨停票常在
 * 5~11 个通达信题材里（09-21 实测 1~11 个），全列出来等于没有信息量。
 *
 * <p>这是<strong>瞬态</strong>对象：不落库。题材成分会变、当日热度每天不同，
 * 落库等于把「今天谁最热」这个判断冻在候选行上。
 */
@Data
public class ThemeTagVO {

    /**
     * 所属股票代码。这是查询的 join 键，不是标签的一部分——一次查回全部候选的题材，
     * 由 service 按 code 分组下发，所以不往响应里塞（每行都重复 6 遍）。
     */
    @JsonIgnore
    private String code;

    /** 板块简称，即通达信板块列表里显示的那个（如「消费电子」）。 */
    private String name;

    /** 板块全称。与简称不同时才在悬浮里补全（如「AI医疗」→「AI医疗概念」）。 */
    private String fullName;

    /** 当日该题材的涨停家数（含本票）。 */
    private int ztCount;

    /** 板块指数代码（通达信 880xxx），便于在海王星里直接对照该板块。 */
    private String indexCode;
}
