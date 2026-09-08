package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 节点状态的建议：平台按盘面明细复算一遍，把「建议是什么、怎么算出来的」一起交出去，
 * 点头才落库（{@code POST /api/nodes/{id}/adopt}）。
 *
 * <p>这个类存在的理由是<b>来路必须跟结论一起出现</b>。只回一个"有效"，他无从判断这个"有效"
 * 是不是他自己那套口径，也不会敢点采纳；把阈值、读数、缺哪一样都摊开，他才是在做决定的人。
 *
 * <p>{@link #missing} 非空 ⇒ {@link #ready} 为 false ⇒ 前端不给采纳按钮。判据不齐时平台<b>不猜</b>：
 * 一条"晋级 0 只"在界面上和"那天根本没拉过明细"长得一模一样，只有这个数组能把两者分开。
 */
@Data
public class NodeSuggestVO {

    private Long nodeId;
    /** 实际按哪套判据算的：A=市场总节点，B=板块节点。节点没填时按 A，并在 {@link #warnings} 里说明。 */
    private String systemType;

    private boolean ready;
    /** 建议落库的状态：待验证 / 有效 / 失效。ready=false 时它是"待验证"，等于不动。 */
    private String suggestedStatus;
    /** 一句话来路，采纳时原样写进 {@code t_node_event.status_note}。 */
    private String reason;

    // ---- 建议写入的八个字段（今天前端一个字都写不进去的那八个），全部与实体同名同类型 ----
    private LocalDate t1Date;
    private Integer t1AnchorRepack;
    private Integer t1PromotionCount;
    private BigDecimal t1PromotionRate;
    private Integer nodeValid;
    private String nodeStock;
    private Integer nodeStockMaxBoard;
    private String status;

    private Promotion promotion;
    private List<FilterItem> filter = new ArrayList<>();
    /** 四条前置过滤器全过才 true；有读数缺就 null——兜成 false 会把"未知"讲成"不通过"。 */
    private Boolean filterAllPass;
    private AnchorReadings anchor;
    private List<String> warnings = new ArrayList<>();
    private List<String> missing = new ArrayList<>();

    /**
     * 八个建议值 + 建议状态的规范 JSON。采纳时必须原样带回来，服务端重算后逐字段比对。
     *
     * <p>用一串规范文本而不是让前端回传八个字段：他要点的就是"这个结论"，中间不该再有一次
     * 序列化把 50.00 变成 50、把 有效 变成别的东西。指纹不一致 ⇒ 那天的盘面在这期间被回补过 ⇒ 拒。
     */
    private String fingerprint;

    /** D0 候选池与 T+1 晋级结果。 */
    @Data
    public static class Promotion {
        private Integer count;
        private Integer total;
        private BigDecimal rate;
        /** 这套判据要几条晋级才算数，带上原文出处。 */
        private String threshold;
        /** 分母是怎么来的，白话一句：哪天、哪个池、几板、有没有用上他手登记的候选。 */
        private String basis;
        private List<Candidate> items = new ArrayList<>();
    }

    /** 一只 D0 候选票的复算结果。 */
    @Data
    public static class Candidate {
        private String code;
        private String name;
        private String industry;
        /** T+1 是否晋级（连板数比 D0 高）。 */
        private Boolean promoted;
        /** T+1 那天的连板数，null=那天它不在涨停池。 */
        private Integer t1Consecutive;
        /** D0 之后（含）在明细里出现过的最高连板数，节点票按这个挑。 */
        private Integer maxBoard;
    }

    /** 前置过滤器的一格：读数、过没过、这个读数是从哪来的。 */
    @Data
    public static class FilterItem {
        private String key;
        private String label;
        /** 格式化后的读数，前端只印不解释；取不到就是"无读数"。 */
        private String actual;
        /** null = 这条没有读数，三态而不是布尔。 */
        private Boolean pass;
        private String source;
    }

    /** 复算时"锚定龙头"到底匹配到了哪只票。名字是他手打的，匹配错了要能当场看出来。 */
    @Data
    public static class AnchorReadings {
        private String input;
        private Boolean matched;
        private String code;
        private String name;
        private String industry;
        /** 它在明细里最后一次涨停的那天，以及那天的连板数。 */
        private String lastLimitDate;
        private Integer lastConsecutive;
    }
}
