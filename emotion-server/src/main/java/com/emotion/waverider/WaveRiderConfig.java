package com.emotion.waverider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WaveRider 的全部可调参数。落库形态是 {@code t_strategy_version.config_json}。
 *
 * <p>参数集中在这一个类里、且版本快照不可变，是为了回答一个具体问题：
 * 「跑出这批候选时，用的到底是哪套参数」。这也是版本表存在的全部理由。
 *
 * <p>JSON 的键沿用 PRD §17 的 snake_case（配置是给人改的，与文档逐字对应比符合 Java 命名更要紧）。
 *
 * <p><strong>关于默认值的一处偏离</strong>：{@code sort_by} 默认取 {@link #SORT_SEAL_STRENGTH}
 * （封单额÷成交额降序），而不是 PRD 附录写的 {@code weighted_score}。
 *
 * <p>依据是把 226 个样本按封单强度分五档、两种买入口径并排算出来的：以「T 日涨停价」
 * 为买点（本策略的真实买点）时，平均收益与胜率沿封单强度<strong>严格单调递增</strong>
 * （+1.71 / +3.55 / +4.37 / +5.40 / +7.56 %；胜率 53% → 87%）；
 * 换成「D+1 开盘价」为买点则严格单调递减（+0.38 → −1.71 %）。
 * 也就是说封单强度本身是正向因子，先前把它读成「买不进的折价」，
 * 只在「D+1 才买」的前提下成立——而那不是本策略的执行方式。
 *
 * <p>{@code score} 仍按 §6.2 公式算并落库，仅作展示与追溯，不参与排序。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WaveRiderConfig {

    public static final String POSITION_MODE_HIGHEST_BOARD = "highest_board";

    public static final String SORT_EXECUTABILITY = "executability";

    /**
     * 按封单额÷成交额<strong>降序</strong>排清单：封单越强越靠前。
     *
     * <p>这是默认值。依据见 {@link #sealLockRatio} 与类注释：在本策略真实的买点
     * （T 日涨停价打板）上，封单强度是唯一跨档严格单调的正向因子。
     */
    public static final String SORT_SEAL_STRENGTH = "seal_strength";

    public static final String SORT_WEIGHTED_SCORE = "weighted_score";
    public static final String SORT_PRINCIPLE_COUNT = "principle_count";

    public static final String ENTRY_BASIS_OPEN_NEXT = "open_next";
    public static final String ENTRY_BASIS_CLOSE_SAME = "close_same";

    public static final String CONFLICT_KEEP = "KEEP";
    public static final String CONFLICT_DROP = "DROP";
    public static final String CONFLICT_MARK = "MARK";

    public static final String THRESHOLD_ABSOLUTE = "absolute";
    public static final String THRESHOLD_DYNAMIC = "dynamic";

    // ---------------------------------------------------------------- 身位板
    @JsonProperty("position_mode")
    private String positionMode = POSITION_MODE_HIGHEST_BOARD;

    /** 低于此连板数不参与身位计算。**这是最容易变成死分支的参数**——调高到 8 会让候选恒为空。 */
    @JsonProperty("min_board_count")
    private int minBoardCount = 2;

    /** 同一身位（同一题材同一高度）最多取几只。 */
    @JsonProperty("max_same_position")
    private int maxSamePosition = 2;

    /** em_industry=东财行业（库内有）；tdx_concept=通达信概念。 */
    @JsonProperty("topic_source")
    private String topicSource = "em_industry";

    /** 龙头断板时是否降级处理。 */
    @JsonProperty("degrade_on_dragon_death")
    private boolean degradeOnDragonDeath = true;

    /** 达到此板数才算「高位」，断板才触发降级。 */
    @JsonProperty("degrade_board_threshold")
    private int degradeBoardThreshold = 4;

    // ---------------------------------------------------------------- 节点票
    /** 自动扫描最近 N 个交易日内的潜在节点。 */
    @JsonProperty("node_scan_window")
    private int nodeScanWindow = 5;

    /** absolute=阈值写死；dynamic=近 20 日分位数自适应（默认，因为写死的绝对阈值必然过期）。 */
    @JsonProperty("threshold_mode")
    private String thresholdMode = THRESHOLD_DYNAMIC;

    /** 各类型节点的评分权重。键与 t_node_detect.node_type 的存储值一致，全部大写。 */
    @JsonProperty("node_type_weights")
    private Map<String, Double> nodeTypeWeights = new LinkedHashMap<String, Double>() {{
        put("START", 1.0);
        put("SWITCH", 0.8);
        put("DIVERGE", 0.6);
    }};

    /** 每题材最多取几只节点票，控制选拔率。 */
    @JsonProperty("node_stock_limit_per_theme")
    private int nodeStockLimitPerTheme = 1;

    /** 选拔率上限，超过则截断并写 CANDIDATE_TRUNCATED 告警。 */
    @JsonProperty("max_select_rate")
    private double maxSelectRate = 0.15;

    // ---------------------------------------------------------------- 过滤
    @JsonProperty("filter_st")
    private boolean filterSt = true;

    /** 上市不足这些自然日的票剔掉。t_stock.listed_at 为 NULL 时不因新股被剔。 */
    @JsonProperty("filter_new_stock_days")
    private int filterNewStockDays = 30;

    /**
     * 成交额下限（<strong>亿元</strong>，与 PRD 附录单位一致，落库前会换算成元）。
     *
     * <p>默认 1.0 而不是 0.5：后者当初的依据「成交额 IC −0.285」在实盘口径下归零到 +0.020。
     * 它现在的实际角色不是「硬剔」，而是配合 {@code filter_conflict_policy=MARK}
     * 给不达标的票打标并按折算仓位处理——之所以这么设计，是因为实测发现
     * 硬剔会把最高身位股（华瓷股份 0.49 亿）一起丢掉。
     */
    @JsonProperty("filter_min_amount")
    private double filterMinAmount = 1.0;

    /** 过滤器与「身位/节点必需条件」冲突时：KEEP=保留 / DROP=剔除 / MARK=保留但打标折算。 */
    @JsonProperty("filter_conflict_policy")
    private String filterConflictPolicy = CONFLICT_MARK;

    /**
     * 封单额÷成交额的<strong>标记</strong>线（1.5 = 150%）。<b>不再用于剔除</b>。
     *
     * <p>它标出的是「这票今天大概率一字，只能在集合竞价挂涨停价排队」——
     * 实测 150~300% 档 94.1%、≥300% 档 100% 在 T 日开盘即封。
     * 「排不排得到队」是<strong>执行</strong>问题，而封单强度本身在<strong>选股</strong>层面是正向的：
     * 这两档的 A 口径收益 +5.40% / +7.56%、胜率 82.4% / 87.0%，是全样本里最好的两组。
     * 因为「买不进」就把最好的一组剔掉，是把执行成本当成了选股依据。
     *
     * <p>保留这个阈值只是为了标出「需排队」的样本（引擎写 {@code SEAL_LOCKED_n} 告警）。
     */
    @JsonProperty("seal_lock_ratio")
    private double sealLockRatio = 1.5;

    // ---------------------------------------------------------------- 排序与评分
    /**
     * 清单排序方式。{@link #SORT_SEAL_STRENGTH 封单强度降序}（默认）｜
     * {@link #SORT_EXECUTABILITY 可执行性升序}。
     *
     * <p>{@code weighted_score} / {@code principle_count} 目前只有枚举、没有实现，
     * 落到默认分支（引擎 {@code rowComparator}）。
     */
    @JsonProperty("sort_by")
    private String sortBy = SORT_SEAL_STRENGTH;

    /**
     * §6.2 评分权重。**当前处于冻结状态**：这套值是在旧口径（T 日涨停价买入）上标定的，
     * 换到实盘口径后各项 IC 基本归零（只有连板数 +0.100→+0.092 跨口径稳健）。
     * 所以它算出来的 score 只作展示与追溯，默认不参与排序，待 B 口径重标定。
     */
    @JsonProperty("score_weights")
    private Map<String, Double> scoreWeights = new LinkedHashMap<String, Double>() {{
        put("board", 0.50);
        put("position", 0.30);
        put("node", 0.20);
        put("timing", 0.10);
        put("risk", 0.20);
    }};

    // ---------------------------------------------------------------- 风控与执行
    /** 单票仓位上限（小数）。 */
    @JsonProperty("max_position_per_stock")
    private double maxPositionPerStock = 0.05;

    /**
     * 收益起算价基准。{@code open_next}=T+1 开盘价（唯一可成交的口径）。
     *
     * <p>候选池的定义是「T 日已涨停」，所以 T 日收盘价在实盘是买不到的。
     * 用 close(T) 当起算价会让全部 alpha 落在「不持仓时的隔夜跳空」上——
     * 实测同一批样本：以 close(T) 起算 +3.36%，以 open(T+1) 起算 −0.20%。
     */
    @JsonProperty("entry_price_basis")
    private String entryPriceBasis = ENTRY_BASIS_OPEN_NEXT;

    /** 隔夜跳空超过此比例就放弃该票（0.03 = 3%）。这是执行纪律，不是择时规律。 */
    @JsonProperty("entry_gap_max")
    private double entryGapMax = 0.03;

    /** 一字/准一字开盘标 UNBUYABLE_OPEN 并计入不可执行。 */
    @JsonProperty("reject_unbuyable_open")
    private boolean rejectUnbuyableOpen = true;

    /** 配置合法性检查。返回人话描述的问题清单，空表示没问题。 */
    public List<String> validate() {
        List<String> errs = new ArrayList<>();
        if (!POSITION_MODE_HIGHEST_BOARD.equals(positionMode)) {
            errs.add("position_mode 只支持 " + POSITION_MODE_HIGHEST_BOARD + "，实得：" + positionMode);
        }
        if (minBoardCount < 1 || minBoardCount > 10) {
            errs.add("min_board_count 应在 1~10，实得：" + minBoardCount
                    + "（调到 8 以上通常会让候选恒为空，请配套看近 20 日触发次数）");
        }
        if (maxSamePosition < 1) {
            errs.add("max_same_position 应 ≥ 1，实得：" + maxSamePosition);
        }
        if (maxPositionPerStock <= 0 || maxPositionPerStock > 1) {
            errs.add("max_position_per_stock 应在 (0,1]，实得：" + maxPositionPerStock);
        }
        if (filterMinAmount < 0) {
            errs.add("filter_min_amount 不能为负，实得：" + filterMinAmount);
        }
        if (sealLockRatio < 0 || sealLockRatio > 10) {
            errs.add("seal_lock_ratio 应在 0~10，实得：" + sealLockRatio);
        }
        if (entryGapMax < 0 || entryGapMax > 0.2) {
            errs.add("entry_gap_max 应在 0~0.2，实得：" + entryGapMax);
        }
        if (!ENTRY_BASIS_OPEN_NEXT.equals(entryPriceBasis)) {
            errs.add("entry_price_basis 只支持 " + ENTRY_BASIS_OPEN_NEXT
                    + "（T+1 开盘价）；以 T 日收盘价起算的收益实盘无法成交，仅可用于回测基准对比");
        }
        if (!isOneOf(sortBy, SORT_EXECUTABILITY, SORT_SEAL_STRENGTH,
                SORT_WEIGHTED_SCORE, SORT_PRINCIPLE_COUNT)) {
            errs.add("sort_by 取值非法：" + sortBy);
        }
        if (!isOneOf(filterConflictPolicy, CONFLICT_KEEP, CONFLICT_DROP, CONFLICT_MARK)) {
            errs.add("filter_conflict_policy 取值非法：" + filterConflictPolicy);
        }
        if (!isOneOf(thresholdMode, THRESHOLD_ABSOLUTE, THRESHOLD_DYNAMIC)) {
            errs.add("threshold_mode 取值非法：" + thresholdMode);
        }
        if (maxSelectRate <= 0 || maxSelectRate > 1) {
            errs.add("max_select_rate 应在 (0,1]，实得：" + maxSelectRate);
        }
        if (nodeTypeWeights == null || nodeTypeWeights.isEmpty()) {
            errs.add("node_type_weights 不能为空");
        } else {
            for (Map.Entry<String, Double> e : nodeTypeWeights.entrySet()) {
                if (!e.getKey().equals(e.getKey().toUpperCase())) {
                    errs.add("node_type_weights 的键必须大写（与 node_type 存储值一致），实得：" + e.getKey());
                }
            }
        }
        if (scoreWeights == null || scoreWeights.isEmpty()) {
            errs.add("score_weights 不能为空");
        }
        return errs;
    }

    private boolean isOneOf(String v, String... allowed) {
        if (v == null) {
            return false;
        }
        for (String a : allowed) {
            if (a.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
