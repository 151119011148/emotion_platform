package com.emotion.vo;

import java.util.List;

import lombok.Data;

/**
 * 「把曲线上那颗 ☆/★ 立成节点」的回执。
 *
 * <p>两行的语义在这里说明白：<b>一次点击可能落两行</b>（成功日会补上前一天那次试探），
 * 也可能一行都不落（同一天同一类型早就立过了）。前端按 {@link #alreadyExists} 决定
 * 是报「已存在，没有重复立」还是报新建了几行，别把幂等显示成两个都成功。
 */
@Data
public class BreakNodeCreateVO {

    /** 立好的行（新插的与原本就在的都在里面，带节点页那套富化）。 */
    private List<NodeVO> rows;
    /** true＝这次一行都没新插，交回来的全是既有行。 */
    private Boolean alreadyExists;
    /** 这一批行会怎么改写节点分；与详情面板上那句同源，落库后再说一遍是为了让回执自己讲得清。 */
    private String scoreImpact;
}
