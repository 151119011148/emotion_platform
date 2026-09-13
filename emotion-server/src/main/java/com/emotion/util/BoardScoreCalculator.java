package com.emotion.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

import com.emotion.market.HighEcoMetrics;

/**
 * 五维双层 0-100 情绪打分引擎（纯函数，不碰 DB）。
 *
 * 输入是一棵 {@link ScoringTree} 配置树快照 + 一张 {@code metrics}（所有自动/人工原始读数，键=source_key）。
 * 引擎按节点的 scoring_kind 递归求值：维/复合子加权合成、BAND_LADDER 走阈值阶梯、STRATEGY 调 Java 命名算法、
 * MANUAL 直接夹 0-100。维分/子分/层分一律 0-100，未评（缺读数）从分母剔除不兜 0，总分=Σ(维分×维权)（全维评了即直加权和）。
 * 求完连板维后按结构信号「中位吹哨」施加 ×0.8；再判结构信号（四层 5 个 + D5 高位生态 7 个）、强制退潮（4+2 条）；最后落 4 个交易纪律带。
 *
 * {@link #builtinTree()} 与 schema.sql 的 five_dim_v2 种子逐字对齐，由 ScoringModelSeedParityTest 钉住（R1：改引擎常量或种子必须跑它）。
 */
public final class BoardScoreCalculator {

    private BoardScoreCalculator() {
    }

    // scoring_kind
    public static final String WEIGHTED_SUM = "WEIGHTED_SUM";
    public static final String LAYER_WEIGHTED_BAND = "LAYER_WEIGHTED_BAND";
    public static final String BAND_LADDER = "BAND_LADDER";
    public static final String STRATEGY = "STRATEGY";
    public static final String MANUAL = "MANUAL";

    // 结构信号名（写进 signal_flags）
    public static final String SIG_WHISTLE = "中位吹哨";
    public static final String SIG_TOP_CROWD = "高位抱团";
    public static final String SIG_CROWD_COLLAPSE = "抱团瓦解前兆";
    public static final String SIG_HIGH_LOW_SWITCH = "高低切";
    public static final String SIG_FULL_EBB = "全面退潮";

    // ---- D5 高位生态信号（2026-09-12 融合版）：旗标由 HighEcoMetricsService 算好放进 metrics ----
    public static final String SIG_DEATH = "死亡结构-抱团崩塌";
    public static final String SIG_MONITOR_IGNORED = "监管无效-情绪亢奋";
    public static final String SIG_MONITOR_WORKS = "监管生效-退潮加速";
    public static final String SIG_ANCHOR_MISMATCH = "龙头与主线错位";
    public static final String SIG_SECTOR_PRESS = "板块级监管压制";
    public static final String SIG_ANCHOR_WEAK = "阵眼走弱";
    public static final String SIG_ANCHOR_TAKEOVER = "龙头易主";
    public static final String SIG_ANCHOR_DEAD = "阵眼失效";

    // 连板「中位吹哨」命中时整维乘数（对齐 schema t_scoring_rule GUARD promo 行 note）
    public static final BigDecimal BOARD_WHISTLE_MULTIPLIER = new BigDecimal("0.8");

    // ---- D2 日内核心结构后处理（控制量由 PrdMetricsService 产，见其 METRIC_* 键）----
    /** 总龙头不在日内核心板块（龙头与主线错位、无合力）时 D2 整维乘数。 */
    public static final BigDecimal DRAGON_MISALIGN_MULTIPLIER = new BigDecimal("0.9");
    /** 日内核心存在但 t_theme 无匹配题材行时，催化剂硬度的保守缺省分（中等，score-detail 标注人工未评）。 */
    public static final int CATALYST_DEFAULT_SCORE = 50;
    /** 空间板不在主线行业时高度聚集度的给分上限：(主线最高板/H)×50。 */
    public static final BigDecimal HEIGHT_OWNERSHIP_MAX = new BigDecimal("50");

    // ---- 连板维 D3 口径修正（2026-09-12）：防止只在连板小圈子内自评导致的逐行虚高 ----
    /** 中位晋级率昨日基数（家数）低于此值 → 中位晋级叶子 ×0.8，小样本不撑"健康"结论。 */
    public static final int JR_MID_SMALL_SAMPLE_BASE = 5;
    public static final BigDecimal JR_MID_SMALL_SAMPLE_MULT = new BigDecimal("0.8");
    /** 空间未打开：H<5（无 ≥5 板，极高层不存在）→ 晋级结构 ×0.8（2026-09-12 由 0.9 下调，退潮日低空间接力更弱）。 */
    public static final int SPACE_OPEN_MIN_HEIGHT = 5;
    public static final BigDecimal PROMO_SPACE_MULT = new BigDecimal("0.8");
    /** 大盘背离（大盘维分&lt;40 或红盘率&lt;20%）→ 溢价结构 ×0.8：抱团溢价不掩盖全局亏钱。 */
    public static final BigDecimal PREMIUM_DIVERGENCE_MARKET_SCORE = new BigDecimal("40");
    public static final double PREMIUM_DIVERGENCE_RED_RATIO = 0.20;
    public static final BigDecimal PREMIUM_DIVERGENCE_MULT = new BigDecimal("0.8");
    /** 全局跌停外溢扣分（作用在大面结构合成分上）：≥20 扣 35 / ≥10 扣 20 / ≥5 扣 8。 */
    public static final int[] BIG_SPILLOVER_DOWN_TIERS = {20, 10, 5};
    public static final int[] BIG_SPILLOVER_DOWN_DEDUCT = {35, 20, 8};
    /** 维分闸门·大盘背离：大盘维分&lt;40 或强制退潮或跌停≥10 → 连板维 ×0.85。 */
    public static final BigDecimal GATE_DIVERGENCE_MARKET_SCORE = new BigDecimal("40");
    public static final int GATE_DIVERGENCE_LIMIT_DOWN = 10;
    public static final BigDecimal GATE_DIVERGENCE_MULT = new BigDecimal("0.85");
    // 注：龙头错位（dragon_misalign）自 2026-09-12 起不再乘 D3 维分，只保留 SIG_ANCHOR_MISMATCH 信号；
    // D2 日内核心维的 ×0.9 维持不变（DRAGON_MISALIGN_MULTIPLIER）。

    // ---- D4 首板生态（纯 T 日）大盘背离时的试错端折扣（条件与 D3 大盘背离闸门同源）----
    /** 大盘背离时首板数量叶子 ×0.85（退潮日试错意愿要打折，PRD 3.2：25-39 只 65×0.85≈55）。 */
    public static final BigDecimal FIRST_DIVERGENCE_COUNT_MULT = new BigDecimal("0.85");
    /** 大盘背离时首板封板率叶子 −10 分（PRD 3.2：70-80% 档 80−10=70）。 */
    public static final int FIRST_DIVERGENCE_SEALED_DEDUCT = 10;

    // 强制退潮阈值
    public static final int FORCED_EBB_LIMIT_DOWN = 10;    // 1. 跌停家数 >= 10
    public static final int FORCED_EBB_JR_MID_PCT = 10;    // 3. 中位晋级率 < 10%
    public static final int FORCED_EBB_BIG_MID = 5;         // 3. 且中位大面 >= 5 家
    public static final int FORCED_EBB_MAX_HEIGHT = 7;      // 4. H >= 7
    public static final BigDecimal FORCED_EBB_TOP_TURNOVER_PCT = new BigDecimal("35"); // 4. 极高位换手 > 35%

    // 交易纪律带（总分 0-100）
    public static final String STAGE_CLIMAX = "高潮";
    public static final String STAGE_FERMENT = "发酵";
    public static final String STAGE_CHAOS = "混沌";
    public static final String STAGE_EBB = "退潮";
    public static final String STAGE_FORCED_EBB = "退潮(强制)";
    public static final BigDecimal BAND_CLIMAX = new BigDecimal("85");
    public static final BigDecimal BAND_FERMENT = new BigDecimal("60");
    public static final BigDecimal BAND_CHAOS = new BigDecimal("40");

    // ---- 大盘生态·指数环境 v2.1：连续函数 score=clamp(50 + 三指均值%×30, 0, 100)，消除 -1% 阈值断层 ----
    public static final BigDecimal INDEX_ENV_NEUTRAL = new BigDecimal("50");
    public static final BigDecimal INDEX_ENV_SLOPE = new BigDecimal("30");
    /** 三指均值 ≤-1.5% 视为暴跌（量价配合系数用）。 */
    public static final BigDecimal INDEX_MEAN_CRASH = new BigDecimal("-1.5");

    // ---- 大盘生态·量能 v2.1.1：价量配合系数（活跃度基础分 × 方向系数，封顶100）----
    public static final BigDecimal VOL_RATIO_HEAVY = new BigDecimal("1.10");  // 放量：量比>=1.10
    public static final BigDecimal VOL_RATIO_SHRINK = new BigDecimal("0.90"); // 缩量：量比<0.90
    public static final BigDecimal VOL_COEFF_RISE_HEAVY = new BigDecimal("1.2");  // 放量上涨：健康放量，加分
    public static final BigDecimal VOL_COEFF_RISE_FLAT = new BigDecimal("1.1");   // 平量上涨
    public static final BigDecimal VOL_COEFF_RISE_SHRINK = new BigDecimal("0.9"); // 缩量上涨(背离)
    // 下跌侧收紧：目标=普跌日(三指均值∈[-1%,0))量能贡献<指数贡献。指数分>=20 → 指数贡献>=0.35×20=7.0；
    // 基础最差档(放量90/平量70/缩量45)×系数 → 6.75/6.125/6.75 全部<7.0。
    public static final BigDecimal VOL_COEFF_DOWN_SHRINK = new BigDecimal("0.6");   // 缩量下跌(抛压不重)
    public static final BigDecimal VOL_COEFF_DOWN_FLAT = new BigDecimal("0.35");   // 平量下跌(9/11口径)
    public static final BigDecimal VOL_COEFF_DOWN_HEAVY = new BigDecimal("0.3");   // 放量下跌(恐慌杀跌)
    public static final BigDecimal VOL_COEFF_CRASH_HEAVY = new BigDecimal("0.15"); // 放量暴跌
    public static final BigDecimal VOL_COEFF_CRASH_FLAT = new BigDecimal("0.25");  // 平量暴跌
    public static final BigDecimal VOL_COEFF_CRASH_SHRINK = new BigDecimal("0.45"); // 缩量暴跌

    // ---- 大盘生态·广度 v2.1 超极端档阈值（double 直供 BandRule.of；0.13≈涨跌家数比>7:1 起惩罚）----
    public static final double BREADTH_RATIO_SEVERE = 0.13;
    public static final double BREADTH_RATIO_PANIC = 0.10;
    public static final double BREADTH_RATIO_DISASTER = 0.05;
    public static final BigDecimal LIMIT_COMBO_STRONG = new BigDecimal("95");
    public static final BigDecimal LIMIT_COMBO_MIXED = new BigDecimal("45");
    public static final BigDecimal LIMIT_COMBO_CRASH = new BigDecimal("5");
    public static final BigDecimal LIMIT_COMBO_MID = new BigDecimal("50");
    public static final BigDecimal ANCHOR_SEALED = new BigDecimal("95");
    public static final BigDecimal ANCHOR_BROKE = new BigDecimal("55");
    public static final BigDecimal ANCHOR_CORE_BUTTON = new BigDecimal("0");
    public static final BigDecimal ANCHOR_MID = new BigDecimal("60");

    /** 一次求值的结果：5 维分 + 总分 + 结构信号 + 强制退潮 + 交易带。 */
    @Data
    public static class Result {
        /** dimKey -> 维分（0-100，四舍五入 2 位）；整维未评则不含该键。 */
        private Map<String, BigDecimal> dimScores = new LinkedHashMap<>();
        /** 维分对应的取数说明（dimKey -> note），供前端「未评≠0」与追溯；可为空。 */
        private Map<String, String> dimNotes = new LinkedHashMap<>();
        private BigDecimal total;                 // 0-100；无任一维可评则 null
        private List<String> signalFlags = new ArrayList<>();
        private boolean forcedEbb;
        private String forcedEbbReason;
        private String stage;                     // null=未出分
        /** 试错-兑现背离度 = 首板生态(D4,T日试错) − 连板生态(D3,T-1→T兑现)；两维任一未评=null。 */
        private BigDecimal ecologyDivergence;
        /** 背离人话标签：严重背离/背离/青黄不接/均衡。 */
        private String ecologyDivergenceLabel;
        /** 每维完整 eval 树（含直属 subs 与层），score-detail 只读端点用；未评的子 score=null。 */
        private List<NodeEval> dimEvals = new ArrayList<>();
    }

    /** 每节点求值结果：score-detail 端点用；未评字段允许 null。
     *  dimNo/recordColumn 仅维层节点填；bandHit 仅 BAND_LADDER 叶填。 */
    @Data
    public static class NodeEval {
        private String key;
        private String label;
        private BigDecimal weight;
        private Integer dimNo;
        private String recordColumn;
        private String scoringKind;
        private String sourceKey;
        private BigDecimal raw;
        private BigDecimal score;
        private String bandHit;
        private String note;
        /**
         * 本日该指标是否适用。false=按当日 H 本就没有这一层（如 H=4 时中高位/极高位），
         * 前端展示 N/A；与"有此层但没采到数据（score=null、applicable 保持 null/true=未评）"区分。
         */
        private Boolean applicable;
        /** 本行口径修正说明（小样本折扣/空间未打开/背离折扣/跌停外溢），用于前端"修正系数"列。 */
        private String adjustment;
        /** 维分闸门（仅连板维填）：中位吹哨/大盘背离/龙头错位，未触发也列出供表尾展示。 */
        private List<GateInfo> gates;
        private List<NodeEval> children = new ArrayList<>();
    }

    /** 维分闸门：一个乘数是否触发及其证据。 */
    @Data
    public static class GateInfo {
        private String key;
        private String label;
        private BigDecimal coefficient;
        private boolean triggered;
        private String reason;

        public GateInfo(String key, String label, BigDecimal coefficient, boolean triggered, String reason) {
            this.key = key;
            this.label = label;
            this.coefficient = coefficient;
            this.triggered = triggered;
            this.reason = reason;
        }
    }


    /**
     * 纯求值：给定配置树 + 原始读数，产出各维分/总分/信号/强制退潮/交易带。不改动入参。
     *
     * @param tree    配置树快照（缺省时调用方应已回退 {@link #builtinTree()}）
     * @param metrics 原始读数表，键=source_key（比率用小数 0-1，涨跌停率/溢价/晋级率/换手用百分数 0-100，家数/板高用计数）
     */
    public static Result evaluate(ScoringTree tree, Map<String, BigDecimal> metrics) {
        Map<String, BigDecimal> m = metrics == null ? new LinkedHashMap<>() : metrics;
        ScoringTree t = tree == null ? builtinTree() : tree;

        List<String> signals = detectSignals(m);
        boolean whistle = signals.contains(SIG_WHISTLE);
        ForcedEbb fe = detectForcedEbb(m);

        // 第一遍：各维裸 eval（不施加维间闸门——大盘背离闸门需要大盘维分先落定）
        Result r = new Result();
        for (DimNode dim : t.getDims()) {
            NodeEval de = evalDim(dim, m);
            r.dimEvals.add(de);
            if (de.getScore() != null) {
                r.dimScores.put(dim.getDimKey(), de.getScore());
            }
        }

        // 第二遍：连板维 D3 口径修正（N/A 标注、叶/子折扣、全局外溢）后再施加维分闸门。
        // 大盘维分在第一遍已可用；闸门系数与证据写进 board 节点 gates 供前端表尾展示。
        NodeEval boardEval = null;
        for (NodeEval de : r.dimEvals) {
            if ("board".equals(de.getKey())) {
                boardEval = de;
                break;
            }
        }
        if (boardEval != null) {
            applyBoardCalibration(boardEval, m, r.dimScores.get("market"), whistle, fe);
            if (boardEval.getScore() != null) {
                r.dimScores.put("board", boardEval.getScore());
            } else {
                r.dimScores.remove("board");
            }
        }

        // 首板维 D4 纯 T 日校准（大盘背离时试错端打折），须在大盘分定盘后
        NodeEval firstEval = null;
        for (NodeEval de : r.dimEvals) {
            if ("first".equals(de.getKey())) {
                firstEval = de;
                break;
            }
        }
        if (firstEval != null) {
            applyFirstCalibration(firstEval, m, r.dimScores.get("market"), fe);
            if (firstEval.getScore() != null) {
                r.dimScores.put("first", firstEval.getScore());
            } else {
                r.dimScores.remove("first");
            }
        }

        // D5 高位维强信号守卫（否决权/无头折扣/强制封顶），须在子项分定盘后
        NodeEval highEval = null;
        for (NodeEval de : r.dimEvals) {
            if ("high".equals(de.getKey())) {
                highEval = de;
                break;
            }
        }
        if (highEval != null) {
            applyHighEcoGuards(highEval, m);
            if (highEval.getScore() != null) {
                r.dimScores.put("high", highEval.getScore());
            } else {
                r.dimScores.remove("high");
            }
        }

        // 试错(D4)-兑现(D3)背离度
        BigDecimal boardScore = r.dimScores.get("board");
        BigDecimal firstScore = r.dimScores.get("first");
        if (boardScore != null && firstScore != null) {
            BigDecimal divergence = firstScore.subtract(boardScore);
            r.ecologyDivergence = divergence;
            double dv = divergence.doubleValue();
            if (dv > 30) {
                r.ecologyDivergenceLabel = "试错-兑现严重背离：今天还在打板，昨天全被埋（接力链将断裂）";
            } else if (dv > 15) {
                r.ecologyDivergenceLabel = "试错-兑现背离：试错意愿尚存但兑现恶化";
            } else if (dv < -15) {
                r.ecologyDivergenceLabel = "青黄不接：昨日晋级票赚钱，但今日新板不足";
            } else {
                r.ecologyDivergenceLabel = "试错-兑现均衡";
            }
        }

        BigDecimal num = BigDecimal.ZERO;
        BigDecimal den = BigDecimal.ZERO;
        for (DimNode dim : t.getDims()) {
            BigDecimal score = r.dimScores.get(dim.getDimKey());
            if (score == null) {
                continue; // 整维未评：剔出分母
            }
            BigDecimal w = BigDecimal.valueOf(dim.getWeight());
            num = num.add(w.multiply(score));
            den = den.add(w);
        }
        if (den.signum() > 0) {
            r.total = clamp0to100(num.divide(den, 6, RoundingMode.HALF_UP)).setScale(2, RoundingMode.HALF_UP);
        }
        r.signalFlags = signals;
        r.forcedEbb = fe.forced;
        r.forcedEbbReason = fe.reason;
        r.stage = stageOf(r.total, r.forcedEbb);
        return r;
    }

    /** 一维完整 eval（含直属 subs 与层）。score 已 clamp 0-100 + HALF_UP 2 位；未评=null。
     *  board 维的吹哨/背离等闸门统一在 {@link #applyBoardCalibration} 施加。 */
    private static NodeEval evalDim(DimNode dim, Map<String, BigDecimal> m) {
        NodeEval de = new NodeEval();
        de.setKey(dim.getDimKey());
        de.setLabel(dim.getLabel());
        de.setDimNo(dim.getDimNo());
        de.setRecordColumn(dim.getRecordColumn());
        de.setWeight(BigDecimal.valueOf(dim.getWeight()));
        de.setScoringKind(WEIGHTED_SUM);
        List<NodeEval> childEvals = new ArrayList<>();
        BigDecimal raw = compositeEval(dim.getSubs(), m, childEvals);
        de.setChildren(childEvals);
        if (raw == null) {
            return de;
        }
        if ("theme_main".equals(dim.getDimKey())) {
            raw = applyThemeMainGuards(raw, m, de);
        }
        de.setScore(clamp0to100(raw).setScale(2, RoundingMode.HALF_UP));
        return de;
    }

    /**
     * 连板维 D3 专属口径修正（2026-09-12），顺序固定：
     * <ol>
     *   <li>按当日 H 标注四层叶子适用性：本日无此层 → applicable=false（N/A，剔出分母），区别于"有层但缺读数"；</li>
     *   <li>中位晋级率小样本（昨日基数&lt;5 家）叶子 ×0.8；</li>
     *   <li>复合子按修正后的叶子重新加权合成（自下而上）；</li>
     *   <li>晋级结构：空间未打开（H&lt;5）×0.8；溢价结构：大盘背离 ×0.8；大面结构：全局跌停外溢 −35/−20/−8；</li>
     *   <li>维分按修正后的五个子项重新加权，再依次过闸门：中位吹哨 ×0.8、大盘背离 ×0.85。</li>
     *       （龙头错位 ×0.9 闸门 2026-09-12 D5 融合起下线，错位只出信号、扣分归 D5 阵眼一致性叶）</li>
     * </ol>
     * 每个修正点都写人话到 adjustment / gates，前端表格逐行可见；控制量缺失（纯引擎单测、非 PrdMetrics 路径）时对应修正不动作。
     */
    private static void applyBoardCalibration(NodeEval board, Map<String, BigDecimal> m,
                                              BigDecimal marketScore, boolean whistle, ForcedEbb fe) {
        BigDecimal hRaw = m.get("max_height");
        Integer h = hRaw == null ? null : hRaw.intValue();

        // 1+2：层叶子 N/A 标注 + 中位晋级小样本（递归到任意深度真叶子，兼容大面「家数+率」3 层）
        List<NodeEval> leaves = new ArrayList<>();
        for (NodeEval sub : board.getChildren()) {
            collectLeaves(sub, leaves);
        }
        for (NodeEval leaf : leaves) {
            Integer li = layerIndexOfMetric(leaf.getSourceKey());
            if (li != null) {
                if (h != null && !layerActive(li, h)) {
                    leaf.setApplicable(false);
                    leaf.setRaw(null);
                    leaf.setScore(null);
                    leaf.setBandHit(null);
                } else {
                    leaf.setApplicable(true);
                }
            }
            if ("jr_mid".equals(leaf.getSourceKey()) && leaf.getScore() != null) {
                BigDecimal base = m.get("jr_mid_base");
                if (base != null && base.signum() > 0
                        && base.compareTo(BigDecimal.valueOf(JR_MID_SMALL_SAMPLE_BASE)) < 0) {
                    leaf.setScore(leaf.getScore().multiply(JR_MID_SMALL_SAMPLE_MULT)
                            .setScale(2, RoundingMode.HALF_UP));
                    leaf.setAdjustment("小样本(昨日基数 " + plain(base) + " 家<"
                            + JR_MID_SMALL_SAMPLE_BASE + ") ×" + plain(JR_MID_SMALL_SAMPLE_MULT));
                }
            }
        }

        // 3：复合子按修正后叶子重新合成（自下而上，先重算层复合再重算大面结构等子项）
        for (NodeEval sub : board.getChildren()) {
            recomputeBottomUp(sub);
        }

        // 4：三个复合子的口径修正
        NodeEval promo = childEval(board, "promo");
        if (promo != null && promo.getScore() != null && h != null && h < SPACE_OPEN_MIN_HEIGHT) {
            promo.setScore(scale(clamp0to100(promo.getScore().multiply(PROMO_SPACE_MULT))));
            promo.setAdjustment("空间未打开（H=" + h + "，无 ≥" + SPACE_OPEN_MIN_HEIGHT
                    + " 板）×" + plain(PROMO_SPACE_MULT));
        }
        NodeEval premium = childEval(board, "premium");
        if (premium != null && premium.getScore() != null) {
            String why = premiumDivergenceReason(marketScore, m);
            if (why != null) {
                premium.setScore(scale(clamp0to100(premium.getScore().multiply(PREMIUM_DIVERGENCE_MULT))));
                premium.setAdjustment("大盘背离（" + why + "）×" + plain(PREMIUM_DIVERGENCE_MULT));
            }
        }
        NodeEval bigloss = childEval(board, "bigloss");
        if (bigloss != null && bigloss.getScore() != null) {
            int deduct = bigSpilloverDeduct(m.get("limit_down_count"));
            if (deduct > 0) {
                BigDecimal ld = m.get("limit_down_count");
                BigDecimal after = clamp0to100(bigloss.getScore().subtract(BigDecimal.valueOf(deduct)));
                bigloss.setScore(scale(after));
                bigloss.setAdjustment("全局跌停 " + plain(ld) + " 家，外溢 −" + deduct);
            }
        }

        // 维分按修正后子项重新加权
        BigDecimal raw = weightedOfChildren(board.getChildren());
        if (raw == null) {
            board.setScore(null);
            return;
        }

        // 5：维分闸门（逐个留证据；未触发也登记，系数 1）
        List<GateInfo> gates = new ArrayList<>();
        gates.add(new GateInfo("whistle", "中位吹哨",
                BOARD_WHISTLE_MULTIPLIER, whistle,
                whistle ? "中位晋级<15%或中位大面≥3家" : "中位晋级率≥15%且中位大面<3家"));
        if (whistle) {
            raw = raw.multiply(BOARD_WHISTLE_MULTIPLIER);
        }
        String divergenceWhy = boardDivergenceReason(marketScore, m, fe);
        boolean divergence = divergenceWhy != null;
        gates.add(new GateInfo("divergence", "大盘背离",
                GATE_DIVERGENCE_MULT, divergence,
                divergence ? divergenceWhy : "大盘分≥40、无强制退潮、跌停<" + GATE_DIVERGENCE_LIMIT_DOWN + " 家"));
        if (divergence) {
            raw = raw.multiply(GATE_DIVERGENCE_MULT);
        }
        // 龙头错位闸门 2026-09-12 起下线：错位只保留信号（detectSignals），扣分归 D5 阵眼一致性叶，
        // 不再在 D2/D3 连乘。
        board.setGates(gates);

        board.setNote(joinGateNotes(gates));
        board.setScore(scale(clamp0to100(raw)));
    }

    /**
     * 首板维 D4 校准（纯 T 日，2026-09-12）：大盘背离（与 D3 闸门同条件）时
     * 首板数量叶 ×0.85、首板封板率叶 −10；再按修正后子项重新合成维分。
     * 控制量缺失（纯引擎单测）时不动作。
     */
    private static void applyFirstCalibration(NodeEval first, Map<String, BigDecimal> m,
                                              BigDecimal marketScore, ForcedEbb fe) {
        String why = boardDivergenceReason(marketScore, m, fe);
        if (why == null) {
            return;
        }
        NodeEval count = childEval(first, "first_count");
        if (count != null && count.getScore() != null) {
            count.setScore(scale(clamp0to100(count.getScore().multiply(FIRST_DIVERGENCE_COUNT_MULT))));
            count.setAdjustment("大盘背离（" + why + "）×" + plain(FIRST_DIVERGENCE_COUNT_MULT));
        }
        NodeEval sealed = childEval(first, "first_sealed");
        if (sealed != null && sealed.getScore() != null) {
            sealed.setScore(scale(clamp0to100(
                    sealed.getScore().subtract(BigDecimal.valueOf(FIRST_DIVERGENCE_SEALED_DEDUCT)))));
            sealed.setAdjustment("大盘背离（" + why + "）−" + FIRST_DIVERGENCE_SEALED_DEDUCT);
        }
        BigDecimal raw = weightedOfChildren(first.getChildren());
        if (raw != null) {
            first.setScore(scale(clamp0to100(raw)));
            first.setNote("大盘背离（" + why + "）：首板数量 ×" + plain(FIRST_DIVERGENCE_COUNT_MULT)
                    + "、首板封板率 −" + FIRST_DIVERGENCE_SEALED_DEDUCT);
        }
    }

    /**
     * D5 高位维强信号守卫（与 service 端 guard 同口径，2026-09-13）。顺序固定：
     * <ol>
     *   <li>监管反馈否决权：核按钮(跌停/大面)≥1 → 监管压制子分 ×0.5；</li>
     *   <li>无头抱团折扣：总龙头失效（龙头易主 level≥3）→ 抱团子分 ×0.85；</li>
     *   <li>按修正后四子项重加权；强制风控（death/top_break）触发时总分封到崩塌顶。</li>
     * </ol>
     * 乘数/封顶与 {@link HighEcoMetrics} 共享常量，改动即改口径、必须连测试一起改。
     */
    private static void applyHighEcoGuards(NodeEval high, Map<String, BigDecimal> m) {
        // ① 监管反馈否决权
        NodeEval pressure = childEval(high, "pressure");
        BigDecimal nuke = m.get("d5f_nuke");
        if (pressure != null && pressure.getScore() != null && nuke != null && nuke.signum() > 0) {
            pressure.setScore(scale(clamp0to100(
                    pressure.getScore().multiply(HighEcoMetrics.GUARD_NUKE_VETO_MULT))));
            pressure.setAdjustment("监管股核按钮 " + nuke.toPlainString()
                    + " 只，压制 ×" + plain(HighEcoMetrics.GUARD_NUKE_VETO_MULT));
        }
        // ② 无头抱团折扣
        NodeEval coalition = childEval(high, "coalition");
        BigDecimal handover = m.get("d5_sig_handover");
        if (coalition != null && coalition.getScore() != null
                && handover != null && handover.intValue() >= 3) {
            coalition.setScore(scale(clamp0to100(
                    coalition.getScore().multiply(HighEcoMetrics.GUARD_HEADLESS_MULT))));
            coalition.setAdjustment("总龙头失效，无头抱团 ×" + plain(HighEcoMetrics.GUARD_HEADLESS_MULT));
        }

        BigDecimal raw = weightedOfChildren(high.getChildren());
        if (raw == null) {
            high.setScore(null);
            return;
        }

        // ③ 强制风控封顶
        boolean force = isOne(m.get("d5_force_death")) || isOne(m.get("d5_force_top_break"));
        List<GateInfo> gates = new ArrayList<>();
        gates.add(new GateInfo("force", "强制风控", BigDecimal.ONE, force, force
                ? "死亡结构/监管龙头断板：总分封至崩塌 ≤" + HighEcoMetrics.GUARD_FORCE_CAP
                : "无强制风控触发"));
        if (force && raw.doubleValue() > HighEcoMetrics.GUARD_FORCE_CAP) {
            raw = BigDecimal.valueOf(HighEcoMetrics.GUARD_FORCE_CAP);
        }
        high.setGates(gates);
        high.setNote(joinGateNotes(gates));
        high.setScore(scale(clamp0to100(raw)));
    }

    private static String joinGateNotes(List<GateInfo> gates) {
        List<String> hit = new ArrayList<>();
        for (GateInfo g : gates) {
            if (g.isTriggered()) {
                hit.add(g.getLabel() + "：本维 ×" + plain(g.getCoefficient()) + "（" + g.getReason() + "）");
            }
        }
        return hit.isEmpty() ? null : String.join("；", hit);
    }

    /** 溢价结构背离折扣的触发证据；不触发返回 null。大盘分<40 或红盘率<20%。 */
    private static String premiumDivergenceReason(BigDecimal marketScore, Map<String, BigDecimal> m) {
        List<String> why = new ArrayList<>();
        if (marketScore != null && marketScore.compareTo(PREMIUM_DIVERGENCE_MARKET_SCORE) < 0) {
            why.add("大盘分 " + plain(marketScore) + "<40");
        }
        BigDecimal red = m.get("red_ratio");
        if (red != null && red.doubleValue() < PREMIUM_DIVERGENCE_RED_RATIO) {
            why.add("红盘率 " + red.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%<20%");
        }
        return why.isEmpty() ? null : String.join("、", why);
    }

    /** 维分闸门·大盘背离：大盘分<40 或强制退潮或跌停≥10。 */
    private static String boardDivergenceReason(BigDecimal marketScore, Map<String, BigDecimal> m, ForcedEbb fe) {
        List<String> why = new ArrayList<>();
        if (marketScore != null && marketScore.compareTo(GATE_DIVERGENCE_MARKET_SCORE) < 0) {
            why.add("大盘分 " + plain(marketScore) + "<40");
        }
        if (fe != null && fe.forced) {
            why.add("已触发强制退潮");
        }
        BigDecimal ld = m.get("limit_down_count");
        if (ld != null && ld.compareTo(BigDecimal.valueOf(GATE_DIVERGENCE_LIMIT_DOWN)) >= 0) {
            why.add("跌停 " + plain(ld) + "≥" + GATE_DIVERGENCE_LIMIT_DOWN + " 家");
        }
        return why.isEmpty() ? null : String.join("、", why);
    }

    /** 全局跌停外溢扣分档位：≥20→35 / ≥10→20 / ≥5→8，其余 0。 */
    private static int bigSpilloverDeduct(BigDecimal limitDown) {
        if (limitDown == null) {
            return 0;
        }
        for (int i = 0; i < BIG_SPILLOVER_DOWN_TIERS.length; i++) {
            if (limitDown.compareTo(BigDecimal.valueOf(BIG_SPILLOVER_DOWN_TIERS[i])) >= 0) {
                return BIG_SPILLOVER_DOWN_DEDUCT[i];
            }
        }
        return 0;
    }

    /**
     * 三层在当日 H 下是否真实存在（与 LadderMetricsService.layerIndex 同一口径）：
     * 低位=2 板（H≥2）、中位=3-4 板（H≥3）、高位=5 板+（H≥空间板高度，对齐高位生态 D5 边界）。
     * H&lt;5 时高位层不存在 → 该层叶子标 N/A、剔除分母不归一化。
     */
    public static boolean layerActive(int layerIndex, int h) {
        switch (layerIndex) {
            case 0: return h >= 2;
            case 1: return h >= 3;
            case 2: return h >= SPACE_OPEN_MIN_HEIGHT;
            default: return true;
        }
    }

    /** 连板三层叶子 source_key（jr_/prem_/big_ 前缀 + low/mid/high）→ 层序号 0..2；非三层叶返回 null。 */
    private static Integer layerIndexOfMetric(String sourceKey) {
        if (sourceKey == null) {
            return null;
        }
        String suffix = null;
        if (sourceKey.startsWith("jr_")) {
            suffix = sourceKey.substring(3);
        } else if (sourceKey.startsWith("prem_")) {
            suffix = sourceKey.substring(5);
        } else if (sourceKey.startsWith("big_")) {
            suffix = sourceKey.substring(4);
            if (suffix.endsWith("_rate")) {
                suffix = suffix.substring(0, suffix.length() - "_rate".length());
            }
        }
        if (suffix == null) {
            return null;
        }
        switch (suffix) {
            case "low": return 0;
            case "mid": return 1;
            case "high":
            case "midhigh":  // 兼容旧数据源：一并归入高位层
            case "top":
                return 2;
            default: return null;
        }
    }

    /** 把节点下的所有真叶子（无子节点）收集进 out，深度不限（兼容大面「家数+率」3 层）。 */
    private static void collectLeaves(NodeEval node, List<NodeEval> out) {
        List<NodeEval> kids = node.getChildren();
        if (kids == null || kids.isEmpty()) {
            out.add(node);
        } else {
            for (NodeEval k : kids) {
                collectLeaves(k, out);
            }
        }
    }

    /** 自下而上重算复合节点：先递归子复合，再用子项加权合成自己；叶子（无子）不动。 */
    private static void recomputeBottomUp(NodeEval node) {
        List<NodeEval> kids = node.getChildren();
        if (kids == null || kids.isEmpty()) {
            return;
        }
        for (NodeEval k : kids) {
            recomputeBottomUp(k);
        }
        BigDecimal v = weightedOfChildren(kids);
        if (v != null) {
            node.setScore(v);
        } else {
            node.setScore(null);
        }
    }

    private static NodeEval childEval(NodeEval parent, String key) {
        for (NodeEval c : parent.getChildren()) {
            if (key.equals(c.getKey())) {
                return c;
            }
        }
        return null;
    }

    /** 节点叶子按权重新加权合成：Σ(权×分)/Σ已评权；全未评=null。applicable=false 的叶子分数已被清空。 */
    private static BigDecimal weightedOfChildren(List<NodeEval> children) {
        BigDecimal num = BigDecimal.ZERO;
        BigDecimal den = BigDecimal.ZERO;
        for (NodeEval c : children) {
            if (c.getScore() == null) {
                continue;
            }
            if (c.getWeight() == null) {
                continue;
            }
            num = num.add(c.getWeight().multiply(c.getScore()));
            den = den.add(c.getWeight());
        }
        if (den.signum() == 0) {
            return null;
        }
        return num.divide(den, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * D2 日内核心结构后处理（2026-09-12 D5 融合后只剩生命周期封顶一道）：
     * 萌芽期单日再热也封顶 50（确认70/扩散85/亢奋100/退潮30）。
     * 龙头错位自 D5 融合起不再在本维扣分（旧 ×0.9 已移除）：错位只输出信号，
     * 扣分统一由 D5 阵眼个体的"主线一致性"叶承担（错位 100→60），避免三处重复计分。
     * 控制量缺失（非 PrdMetrics 取数路径，如纯引擎单测）时不动作。
     */
    private static BigDecimal applyThemeMainGuards(BigDecimal raw, Map<String, BigDecimal> m, NodeEval de) {
        if (raw == null) {
            return null;
        }
        BigDecimal cap = m.get("mainline_stage_cap");
        if (cap != null && raw.compareTo(cap) > 0) {
            raw = clamp0to100(cap);
            appendDimNote(de, "生命周期阶段天花板：本维封顶 " + plain(cap));
        }
        return raw;
    }

    private static void appendDimNote(NodeEval de, String note) {
        String prev = de.getNote();
        de.setNote(prev == null || prev.isEmpty() ? note : prev + "；" + note);
    }

    /** WEIGHTED_SUM / LAYER_WEIGHTED_BAND 通用（NodeEval 版）：Σ(子权×子分)/Σ(已评子权)；childEvals 出参。 */
    private static BigDecimal compositeEval(List<SubNode> children, Map<String, BigDecimal> m, List<NodeEval> childEvals) {
        if (children == null || children.isEmpty()) {
            return null;
        }
        BigDecimal num = BigDecimal.ZERO;
        BigDecimal den = BigDecimal.ZERO;
        for (SubNode child : children) {
            NodeEval ce = evalSubNode(child, m);
            childEvals.add(ce);
            BigDecimal s = ce.getScore();
            if (s == null) {
                continue;
            }
            BigDecimal w = BigDecimal.valueOf(child.getWeight());
            num = num.add(w.multiply(s));
            den = den.add(w);
        }
        if (den.signum() == 0) {
            return null;
        }
        return num.divide(den, 6, RoundingMode.HALF_UP);
    }

    /** 单节点求值（NodeEval 版），按 scoringKind 分派；未评时 score=null 但 children 仍填。 */
    private static NodeEval evalSubNode(SubNode node, Map<String, BigDecimal> m) {
        NodeEval e = new NodeEval();
        if (node == null) {
            return e;
        }
        e.setKey(node.getSubKey());
        e.setLabel(node.getLabel());
        e.setWeight(BigDecimal.valueOf(node.getWeight()));
        e.setScoringKind(node.getScoringKind());
        e.setSourceKey(node.getSourceKey());
        String kind = node.getScoringKind();
        if (WEIGHTED_SUM.equals(kind) || LAYER_WEIGHTED_BAND.equals(kind)) {
            List<NodeEval> kids = new ArrayList<>();
            BigDecimal s = compositeEval(node.getChildren(), m, kids);
            e.setChildren(kids);
            e.setScore(s);
            return e;
        }
        if (BAND_LADDER.equals(kind)) {
            BigDecimal raw = m.get(node.getSourceKey());
            e.setRaw(raw);
            if (raw != null) {
                BandRule hit = findBand(raw, node.getLadder());
                if (hit != null) {
                    e.setScore(hit.getScore());
                    e.setBandHit(renderBand(raw, hit));
                }
            }
            return e;
        }
        if (MANUAL.equals(kind)) {
            BigDecimal raw = m.get(node.getSourceKey());
            e.setRaw(raw);
            if (raw != null) {
                e.setScore(clamp0to100(raw));
            }
            return e;
        }
        if (STRATEGY.equals(kind)) {
            e.setScore(strategy(node.getSourceKey(), m));
            e.setNote(strategyNote(node.getSourceKey(), m));
            return e;
        }
        return e;
    }

    /** 命中档的人话串："1.15 [1,1.3] → 60" 之类，只用于 tooltip。 */
    private static String renderBand(BigDecimal raw, BandRule rule) {
        StringBuilder sb = new StringBuilder();
        sb.append(plain(raw)).append(' ');
        String op = rule.getOperator();
        if ("GTE".equals(op)) sb.append("≥ ").append(plain(rule.getThresholdLow()));
        else if ("GT".equals(op)) sb.append("> ").append(plain(rule.getThresholdLow()));
        else if ("LTE".equals(op)) sb.append("≤ ").append(plain(rule.getThresholdLow()));
        else if ("LT".equals(op)) sb.append("< ").append(plain(rule.getThresholdLow()));
        else if ("EQ".equals(op)) sb.append("= ").append(plain(rule.getThresholdLow()));
        else if ("BETWEEN".equals(op)) sb.append("[").append(plain(rule.getThresholdLow()))
                .append(',').append(plain(rule.getThresholdHigh())).append(']');
        else if ("ELSE".equals(op)) sb.append("兜底");
        else if (op != null) sb.append(op);
        if (rule.getScore() != null) {
            sb.append(" → ").append(plain(rule.getScore()));
        }
        return sb.toString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    /** 数值版 composite：委托 compositeEval。 */
    private static BigDecimal composite(List<SubNode> children, Map<String, BigDecimal> m) {
        return compositeEval(children, m, new ArrayList<NodeEval>());
    }

    /** 单节点求值（数值版）：委托 evalSubNode 取 score。 */
    private static BigDecimal evalNode(SubNode node, Map<String, BigDecimal> m) {
        return evalSubNode(node, m).getScore();
    }

    /** 有序阈值阶梯：命中第一条即返回其 score。 */
    private static BigDecimal band(BigDecimal raw, List<BandRule> ladder) {
        BandRule hit = findBand(raw, ladder);
        return hit == null ? null : hit.getScore();
    }

    /** 返回命中的第一条 BandRule；raw=null 或全不中返回 null。 */
    private static BandRule findBand(BigDecimal raw, List<BandRule> ladder) {
        if (raw == null || ladder == null) {
            return null;
        }
        for (BandRule rule : ladder) {
            if (matches(raw, rule)) {
                return rule;
            }
        }
        return null;
    }

    private static boolean matches(BigDecimal raw, BandRule rule) {
        String op = rule.getOperator();
        if (op == null) {
            return false;
        }
        BigDecimal lo = rule.getThresholdLow();
        BigDecimal hi = rule.getThresholdHigh();
        switch (op) {
            case "GTE":
                return lo != null && raw.compareTo(lo) >= 0;
            case "GT":
                return lo != null && raw.compareTo(lo) > 0;
            case "LTE":
                return lo != null && raw.compareTo(lo) <= 0;
            case "LT":
                return lo != null && raw.compareTo(lo) < 0;
            case "EQ":
                return lo != null && raw.compareTo(lo) == 0;
            case "BETWEEN":
                return lo != null && hi != null && raw.compareTo(lo) >= 0 && raw.compareTo(hi) <= 0;
            case "ELSE":
                return true;
            default:
                return false; // COMPOUND/GUARD/AGG 结构行不参与阶梯命中
        }
    }

    /** 不可约策略 dispatch（算法体留 Java；其展示档位登记在 t_scoring_rule，由 ParityTest 钉住）。 */
    private static BigDecimal strategy(String name, Map<String, BigDecimal> m) {
        if (name == null) {
            return null;
        }
        switch (name) {
            case "index_env":
                return strategyIndexEnv(m);
            case "turnover":
                return strategyTurnover(m);
            case "limit_combo":
                return strategyLimitCombo(m);
            case "board_anchor":
                return strategyBoardAnchor(m);
            case "height_gather":
                return strategyHeightGather(m);
            case "catalyst":
                return strategyCatalyst(m);
            case "d5_feedback":
                return strategyD5Feedback(m);
            default:
                return null;
        }
    }

    /** STRATEGY 节点的人话说明（前端「读数/命中档」列展示；未评返回 null）。 */
    private static String strategyNote(String name, Map<String, BigDecimal> m) {
        switch (name) {
            case "index_env":
                return indexEnvNote(m);
            case "turnover":
                return turnoverNote(m);
            case "limit_combo":
                return limitComboNote(m);
            case "height_gather":
                return heightGatherNote(m);
            case "catalyst":
                return catalystNote(m);
            case "d5_feedback":
                return d5FeedbackNote(m);
            default:
                return null;
        }
    }

    /** 指数环境 v2.1：连续函数 score=clamp(50 + 三指均值%×30, 0, 100)。缺任一指数=未评。
     *  锚点：跌0%→50、跌1%→20、跌2%→0(封底)、平盘=中性50、涨1%→80、涨1.5%→95。 */
    static BigDecimal strategyIndexEnv(Map<String, BigDecimal> m) {
        BigDecimal mean = indexMean(m);
        if (mean == null) {
            return null;
        }
        return clamp0to100(INDEX_ENV_NEUTRAL.add(mean.multiply(INDEX_ENV_SLOPE)));
    }

    /** 量能 v2.1：活跃度基础分（量比阶梯）× 价量配合系数。缺量比=未评；缺指数方向=系数1.0（不砍量能）。 */
    static BigDecimal strategyTurnover(Map<String, BigDecimal> m) {
        BigDecimal t = m.get("turnover_ratio");
        if (t == null) {
            return null;
        }
        BigDecimal base = band(t, TURNOVER_BASE_LADDER);
        if (base == null) {
            return null;
        }
        BigDecimal mean = indexMean(m);
        return clamp0to100(base.multiply(pvCoeff(t, mean)));
    }

    /** 价量配合系数：放量涨1.2/平量涨1.1/缩量涨0.9/缩量跌0.6/平量跌0.35/放量跌0.3/放量暴跌0.15。 */
    private static BigDecimal pvCoeff(BigDecimal ratio, BigDecimal mean) {
        boolean heavy = ratio.compareTo(VOL_RATIO_HEAVY) >= 0;
        boolean shrink = ratio.compareTo(VOL_RATIO_SHRINK) < 0;
        if (mean == null || mean.signum() == 0) {
            return BigDecimal.ONE; // 方向未知 / 平盘：中性
        }
        if (mean.signum() > 0) {
            if (heavy) {
                return VOL_COEFF_RISE_HEAVY;
            }
            return shrink ? VOL_COEFF_RISE_SHRINK : VOL_COEFF_RISE_FLAT;
        }
        if (mean.compareTo(INDEX_MEAN_CRASH) <= 0) { // 暴跌：三指均值<=-1.5%
            if (heavy) {
                return VOL_COEFF_CRASH_HEAVY;
            }
            return shrink ? VOL_COEFF_CRASH_SHRINK : VOL_COEFF_CRASH_FLAT;
        }
        if (heavy) {
            return VOL_COEFF_DOWN_HEAVY;
        }
        return shrink ? VOL_COEFF_DOWN_SHRINK : VOL_COEFF_DOWN_FLAT;
    }

    private static String indexEnvNote(Map<String, BigDecimal> m) {
        BigDecimal mean = indexMean(m);
        if (mean == null) {
            return null;
        }
        BigDecimal score = clamp0to100(INDEX_ENV_NEUTRAL.add(mean.multiply(INDEX_ENV_SLOPE)));
        return "三指均值 " + plain(mean) + "% → 连续函数 50+(" + plain(mean) + ")×30 = " + plain(score);
    }

    private static String turnoverNote(Map<String, BigDecimal> m) {
        BigDecimal t = m.get("turnover_ratio");
        if (t == null) {
            return null;
        }
        BigDecimal base = band(t, TURNOVER_BASE_LADDER);
        if (base == null) {
            return null;
        }
        BigDecimal mean = indexMean(m);
        BigDecimal coeff = pvCoeff(t, mean);
        return "量比" + plain(t) + " → 基础" + plain(base) + " × " + pvName(t, mean) + plain(coeff) + " → "
                + plain(clamp0to100(base.multiply(coeff)));
    }

    private static String pvName(BigDecimal ratio, BigDecimal mean) {
        boolean heavy = ratio.compareTo(VOL_RATIO_HEAVY) >= 0;
        boolean shrink = ratio.compareTo(VOL_RATIO_SHRINK) < 0;
        if (mean == null) {
            return "方向未知×";
        }
        if (mean.signum() == 0) {
            return "平盘×";
        }
        if (mean.signum() > 0) {
            return (heavy ? "放量上涨" : (shrink ? "缩量上涨" : "平量上涨")) + "×";
        }
        if (mean.compareTo(INDEX_MEAN_CRASH) <= 0) {
            return (heavy ? "放量暴跌" : (shrink ? "缩量暴跌" : "平量暴跌")) + "×";
        }
        return (heavy ? "放量下跌" : (shrink ? "缩量下跌" : "平量下跌")) + "×";
    }

    /** 涨跌停结构标签（不影响总分；如 9/11 涨停40+跌停21=极端分化）。 */
    private static String limitComboNote(Map<String, BigDecimal> m) {
        BigDecimal lu = m.get("limit_up_count");
        BigDecimal ld = m.get("limit_down_count");
        if (lu == null || ld == null) {
            return null;
        }
        if (ld.compareTo(new BigDecimal("20")) > 0) {
            return lu.compareTo(new BigDecimal("35")) >= 0
                    ? "极端分化：跌停>20 但涨停≥35（局部资金抱团）" : "跌停占优(>20)";
        }
        if (lu.compareTo(new BigDecimal("80")) >= 0 && ld.signum() == 0) {
            return "全面涨停";
        }
        if (lu.compareTo(new BigDecimal("40")) >= 0 && lu.compareTo(new BigDecimal("60")) <= 0
                && ld.compareTo(new BigDecimal("5")) >= 0 && ld.compareTo(new BigDecimal("8")) <= 0) {
            return "涨跌停夹杂";
        }
        return "涨跌停中性区间";
    }

    /** 核心三指均值（百分点）：000001/399001/399006 严格只这三指，北证/科创仅供展示不进分。 */
    private static BigDecimal indexMean(Map<String, BigDecimal> m) {
        BigDecimal a = m.get("index1_pct");
        BigDecimal b = m.get("index2_pct");
        BigDecimal c = m.get("index3_pct");
        if (a == null || b == null || c == null) {
            return null;
        }
        return a.add(b).add(c).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
    }

    /** 涨跌停：涨停/跌停两操作数组合阶梯。缺任一=未评。 */
    static BigDecimal strategyLimitCombo(Map<String, BigDecimal> m) {
        BigDecimal lu = m.get("limit_up_count");
        BigDecimal ld = m.get("limit_down_count");
        if (lu == null || ld == null) {
            return null;
        }
        if (lu.compareTo(new BigDecimal("80")) >= 0 && ld.signum() == 0) {
            return LIMIT_COMBO_STRONG;  // 涨停>=80 且 跌停=0
        }
        if (lu.compareTo(new BigDecimal("40")) >= 0 && lu.compareTo(new BigDecimal("60")) <= 0
                && ld.compareTo(new BigDecimal("5")) >= 0 && ld.compareTo(new BigDecimal("8")) <= 0) {
            return LIMIT_COMBO_MIXED;   // 涨停40~60 且 跌停5~8
        }
        if (ld.compareTo(new BigDecimal("20")) > 0) {
            return LIMIT_COMBO_CRASH;   // 跌停>20
        }
        return LIMIT_COMBO_MID;         // 其余
    }

    /** 阵眼(空间板/核心龙)：状态分×监管折扣。读数全缺=未评。 */
    static BigDecimal strategyBoardAnchor(Map<String, BigDecimal> m) {
        BigDecimal limitDown = m.get("anchor_limit_down"); // 1=收盘跌停/核按钮
        BigDecimal broke = m.get("anchor_broke");          // 1=爆量断板
        BigDecimal sealed = m.get("anchor_sealed");        // 1=涨停/一字封住
        if (limitDown == null && broke == null && sealed == null) {
            return null;
        }
        BigDecimal base;
        if (isOne(limitDown)) {
            base = ANCHOR_CORE_BUTTON;  // 核按钮/跌停
        } else if (isOne(broke)) {
            base = ANCHOR_BROKE;        // 爆量断板
        } else if (isOne(sealed)) {
            base = ANCHOR_SEALED;       // 一字/涨停封住
        } else {
            base = ANCHOR_MID;
        }
        BigDecimal discount = m.get("anchor_supervision_discount"); // 0-1 乘数，缺省=不打折
        if (discount != null && discount.signum() > 0) {
            base = base.multiply(discount);
        }
        return base;
    }

    /**
     * 高度聚集度（空间板归属口径）：
     * 空间板（全市场 H）在主线行业 → 按板数比走原阶梯（≥90→95/≥70→85/≥50→70/≥30→50/else28）；
     * 不在主线 → (主线最高板/H)×50，最多 50（元件案例 2/4×50=25，而不是板数比 50% 给 70）；
     * 归属旗标缺失（非自动取数路径）→ 退原阶梯，绝不外推归属。缺高度比=未评。
     */
    static BigDecimal strategyHeightGather(Map<String, BigDecimal> m) {
        BigDecimal pct = m.get("height_gather_pct");
        if (pct == null) {
            return null;
        }
        BigDecimal flag = m.get("space_board_in_main");
        if (flag != null && !isOne(flag)) {
            BigDecimal owned = pct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)
                    .multiply(HEIGHT_OWNERSHIP_MAX);
            return clamp0to100(owned);
        }
        return band(pct, HEIGHT_GATHER_LADDER);
    }

    private static String heightGatherNote(Map<String, BigDecimal> m) {
        BigDecimal pct = m.get("height_gather_pct");
        if (pct == null) {
            return null;
        }
        BigDecimal score = strategyHeightGather(m);
        BigDecimal flag = m.get("space_board_in_main");
        if (flag != null && !isOne(flag)) {
            return "空间板不在主线行业：(" + plain(pct) + "%/100)×50 = " + plain(score)
                    + "（板数比再高也封顶 50）";
        }
        return "空间板在主线行业：高度比 " + plain(pct) + "% 走阶梯 → " + plain(score);
    }

    /**
     * 催化剂硬度：t_theme 给了硬度 1-5 → 100/80/60/40/20；
     * 日内核心存在（main_sector_active=1）但没有匹配题材行 → 保守中等 50，并在依据串标注人工未评；
     * 日内核心都不存在（无涨停池）→ 未评，不凭空出分。
     */
    static BigDecimal strategyCatalyst(Map<String, BigDecimal> m) {
        BigDecimal hardness = m.get("catalyst_hardness");
        if (hardness != null) {
            return band(hardness, CATALYST_LADDER);
        }
        return m.get("main_sector_active") == null ? null : BigDecimal.valueOf(CATALYST_DEFAULT_SCORE);
    }

    private static String catalystNote(Map<String, BigDecimal> m) {
        BigDecimal hardness = m.get("catalyst_hardness");
        if (hardness != null) {
            return "题材催化硬度 " + plain(hardness) + " 星 → " + plain(band(hardness, CATALYST_LADDER));
        }
        if (m.get("main_sector_active") != null) {
            return "无匹配题材行，按中等 " + CATALYST_DEFAULT_SCORE + "（人工未评，去 t_theme 维护硬度替换）";
        }
        return null;
    }

    private static boolean isOne(BigDecimal v) {
        return v != null && v.signum() != 0;
    }

    /**
     * D5 监管反馈五态（PRD 子项4）：核按钮≥1→0；否则均涨 >5→70（监管无效/亢奋）、>0→85（红盘消化）、
     * >-7→50（绿盘分歧）、其余 25。d5f_nuke 缺席=整支未评；nuke=0 但均价缺=未评，不兜中位数。
     */
    static BigDecimal strategyD5Feedback(Map<String, BigDecimal> m) {
        BigDecimal nuke = m.get("d5f_nuke");
        if (nuke == null) {
            return null;
        }
        if (nuke.signum() >= 1) {
            return BigDecimal.ZERO;
        }
        BigDecimal avg = m.get("d5f_avg");
        if (avg == null) {
            return null;
        }
        if (avg.compareTo(new BigDecimal("5")) > 0) {
            return new BigDecimal("70");
        }
        if (avg.signum() > 0) {
            return new BigDecimal("85");
        }
        if (avg.compareTo(new BigDecimal("-7")) > 0) {
            return new BigDecimal("50");
        }
        return new BigDecimal("25");
    }

    private static String d5FeedbackNote(Map<String, BigDecimal> m) {
        BigDecimal nuke = m.get("d5f_nuke");
        if (nuke == null) {
            return null;
        }
        BigDecimal score = strategyD5Feedback(m);
        if (score == null) {
            return "在列但全员缺当日涨跌，监管反馈未评";
        }
        BigDecimal avg = m.get("d5f_avg");
        if (nuke.signum() >= 1) {
            return "监管股核按钮/跌停 " + nuke.toPlainString() + " 只 → 0（抱团瓦解确认）";
        }
        return "核按钮 0、进分股均涨 " + plain(avg) + "% → " + plain(score);
    }

    /** 5 个结构信号（可多选）；只在该信号所需读数齐备时才可能命中。 */
    static List<String> detectSignals(Map<String, BigDecimal> m) {
        List<String> out = new ArrayList<>();
        BigDecimal jrLow = m.get("jr_low");
        BigDecimal jrMid = m.get("jr_mid");
        BigDecimal jrHigh = m.get("jr_high");
        BigDecimal bigMid = m.get("big_mid");

        // 中位吹哨（2026-09-12：溢价从硬条件里撤离，中位大面≥3 家单独即吹）：
        // 中位晋级率<15%，或中位大面≥3 家。
        boolean whistle = (jrMid != null && jrMid.compareTo(new BigDecimal("15")) < 0)
                || (bigMid != null && bigMid.compareTo(new BigDecimal("3")) >= 0);
        if (whistle) {
            out.add(SIG_WHISTLE);
        }
        if (jrHigh != null && jrMid != null && jrLow != null
                && jrHigh.compareTo(new BigDecimal("50")) >= 0
                && jrMid.compareTo(new BigDecimal("25")) < 0
                && jrLow.compareTo(new BigDecimal("25")) < 0) {
            out.add(SIG_TOP_CROWD);
        }
        // 中高/极高位断层信号取消：去重后中高位并入高位(5板+)，无断层可判；
        // SIG_CROWD_COLLAPSE 仍由 D5 高位生态的 coalition_risk 交叉信号触发。
        if (jrLow != null && jrHigh != null
                && jrLow.compareTo(new BigDecimal("40")) >= 0
                && jrHigh.compareTo(new BigDecimal("30")) < 0) {
            out.add(SIG_HIGH_LOW_SWITCH);
        }
        if (jrLow != null && jrMid != null && jrHigh != null
                && jrLow.compareTo(new BigDecimal("15")) < 0
                && jrMid.compareTo(new BigDecimal("15")) < 0
                && jrHigh.compareTo(new BigDecimal("20")) < 0) {
            out.add(SIG_FULL_EBB);
        }

        // ---- D5 高位生态交叉信号（旗标在 HighEcoMetricsService 算好，引擎只翻译成标签） ----
        addSignal(out, m, "d5_sig_coalition_risk", SIG_CROWD_COLLAPSE);
        addSignal(out, m, "d5_sig_death", SIG_DEATH);
        addSignal(out, m, "d5_sig_monitor_ignored", SIG_MONITOR_IGNORED);
        addSignal(out, m, "d5_sig_monitor_works", SIG_MONITOR_WORKS);
        if (isOne(m.get("dragon_misalign"))) {
            addSignal(out, m, "dragon_misalign", SIG_ANCHOR_MISMATCH);
        }
        addSignal(out, m, "d5_sig_sector_press", SIG_SECTOR_PRESS);
        BigDecimal handover = m.get("d5_sig_handover");
        if (handover != null) {
            int level = handover.intValue();
            if (level >= 3) {
                out.add(SIG_ANCHOR_DEAD);
            } else if (level == 2) {
                out.add(SIG_ANCHOR_TAKEOVER);
            } else if (level == 1) {
                out.add(SIG_ANCHOR_WEAK);
            }
        }
        return out;
    }

    /** 旗标=1 时登记信号；同名信号（如两处都会出的"抱团瓦解前兆"）只保留一条。 */
    private static void addSignal(List<String> out, Map<String, BigDecimal> m, String flagKey, String label) {
        if (isOne(m.get(flagKey)) && !out.contains(label)) {
            out.add(label);
        }
    }

    static final class ForcedEbb {
        boolean forced;
        String reason;
    }

    /**
     * 强制退潮任一触发（无视总分）：
     * 1 跌停>=10；2 阵眼跌停/核按钮；3 中位晋级<10%且中位大面>=5；4 极高位爆量断板；
     * 5（D5）空间板处于 SEVERE/EXCH 监管且当日断板/核按钮；6（D5）监管股核按钮≥1 且空间板唯一（死亡结构）。
     */
    static ForcedEbb detectForcedEbb(Map<String, BigDecimal> m) {
        ForcedEbb f = new ForcedEbb();
        List<String> reasons = new ArrayList<>();

        BigDecimal ld = m.get("limit_down_count");
        if (ld != null && ld.compareTo(new BigDecimal(FORCED_EBB_LIMIT_DOWN)) >= 0) {
            reasons.add("跌停家数" + ld.stripTrailingZeros().toPlainString() + ">=" + FORCED_EBB_LIMIT_DOWN);
        }
        if (isOne(m.get("anchor_limit_down"))) {
            reasons.add("阵眼核按钮/收盘跌停");
        }
        BigDecimal jrMid = m.get("jr_mid");
        BigDecimal bigMid = m.get("big_mid");
        if (jrMid != null && bigMid != null
                && jrMid.compareTo(new BigDecimal(FORCED_EBB_JR_MID_PCT)) < 0
                && bigMid.compareTo(new BigDecimal(FORCED_EBB_BIG_MID)) >= 0) {
            reasons.add("中位晋级率<" + FORCED_EBB_JR_MID_PCT + "%且中位大面>=" + FORCED_EBB_BIG_MID + "家");
        }
        BigDecimal h = m.get("max_height");
        BigDecimal topTurnover = m.get("top_high_turnover_pct");
        BigDecimal topBreak = m.get("top_high_break"); // 1=爆量断板未回封
        if (h != null && topTurnover != null && isOne(topBreak)
                && h.compareTo(new BigDecimal(FORCED_EBB_MAX_HEIGHT)) >= 0
                && topTurnover.compareTo(FORCED_EBB_TOP_TURNOVER_PCT) > 0) {
            reasons.add("极高位(H>=" + FORCED_EBB_MAX_HEIGHT + ")爆量断板且换手>" + FORCED_EBB_TOP_TURNOVER_PCT.toPlainString() + "%");
        }
        if (isOne(m.get("d5_force_top_break"))) {
            reasons.add("空间板处于严重异动/交易所监管期且当日断板或核按钮");
        }
        if (isOne(m.get("d5_force_death"))) {
            reasons.add("死亡结构：监管股核按钮≥1且空间板唯一，全市场空仓");
        }

        if (!reasons.isEmpty()) {
            f.forced = true;
            f.reason = String.join("；", reasons);
        }
        return f;
    }

    /** 交易纪律带；命中强制退潮→退潮(强制)，无视 total。total=null 不出带。 */
    static String stageOf(BigDecimal total, boolean forcedEbb) {
        if (forcedEbb) {
            return STAGE_FORCED_EBB;
        }
        if (total == null) {
            return null;
        }
        if (total.compareTo(BAND_CLIMAX) >= 0) {
            return STAGE_CLIMAX;
        }
        if (total.compareTo(BAND_FERMENT) >= 0) {
            return STAGE_FERMENT;
        }
        if (total.compareTo(BAND_CHAOS) >= 0) {
            return STAGE_CHAOS;
        }
        return STAGE_EBB;
    }

    private static BigDecimal clamp0to100(BigDecimal v) {
        if (v == null) {
            return null;
        }
        if (v.signum() < 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal max = new BigDecimal("100");
        return v.compareTo(max) > 0 ? max : v;
    }

    // ============================ 内置默认树（= schema five_dim 种子） ============================

    /** 与 schema.sql five_dim 种子逐字对齐的内置兜底树；读不到 DB 配置时用（改种子须同步此方法并跑 ParityTest）。 */
    public static ScoringTree builtinTree() {
        List<DimNode> dims = new ArrayList<>();
        dims.add(marketDim());
        dims.add(themeMainDim());
        dims.add(boardDim());
        dims.add(firstDim());
        dims.add(highEcoDim());
        return new ScoringTree("five_dim_v2", "五维双层情绪模型 v3(D5融合)", 100.0, dims);
    }

    /** 量能基础分阶梯（活跃度，未乘价量系数）：原生五维口径不变。 */
    private static final List<BandRule> TURNOVER_BASE_LADDER = ladder(
            BandRule.of("GTE", 1.20, null, 90),
            BandRule.of("GTE", 0.95, null, 70),
            BandRule.of("GTE", 0.70, null, 45),
            BandRule.of("ELSE", null, null, 25));

    /** 广度 v2.1：原 <20%→10 一档拆成 <0.13 起逐级惩罚（≈涨跌家数比>7:1 扣 5，0.10 再扣、0.05 清零）。
     *  0.13 用 2 位小数对齐 t_scoring_rule.threshold_low DECIMAL(10,2)，0.125 会被库舍入成 0.13。 */
    private static final List<BandRule> BREADTH_LADDER_V21 = ladder(
            BandRule.of("GTE", 0.60, null, 85),
            BandRule.of("GTE", 0.40, null, 55),
            BandRule.of("GTE", 0.20, null, 30),
            BandRule.of("GTE", BREADTH_RATIO_SEVERE, null, 10),
            BandRule.of("GTE", BREADTH_RATIO_PANIC, null, 5),
            BandRule.of("GTE", BREADTH_RATIO_DISASTER, null, 0),
            BandRule.of("ELSE", null, null, 0));

    private static DimNode marketDim() {
        List<SubNode> subs = new ArrayList<>();
        subs.add(SubNode.strategy("index_env", "指数环境", 0.35, "index_env"));
        subs.add(SubNode.strategy("turnover", "量能", 0.25, "turnover"));
        subs.add(SubNode.band("breadth", "广度", 0.20, "red_ratio", BREADTH_LADDER_V21));
        subs.add(SubNode.strategy("limit_combo", "涨跌停", 0.20, "limit_combo"));
        return new DimNode("market", "大盘生态", 0.22, 1, "score_market", subs);
    }

    private static DimNode themeMainDim() {
        // PRD 2.0 五要素：涨停聚集度25/高度聚集度25/成交额聚集度20/催化剂硬度15/持续性15。
        // 自动取数在 PrdMetricsService：amount_gather_pct 固定涨停股口径（主线涨停股amount/全部涨停股amount），不接受人工覆盖；
        // height_gather/catalyst 是 STRATEGY——空间板归属砍半、无题材行默认 50 的判断在 Java。
        // 维分另有两道结构后处理：生命周期阶段天花板 + 龙头错位 ×0.9（见 applyThemeMainGuards）。
        List<SubNode> subs = new ArrayList<>();
        subs.add(SubNode.band("zt_gather", "涨停聚集度", 0.25, "zt_gather_pct", ladder(
                BandRule.of("GTE", 40.0, null, 95),
                BandRule.of("GTE", 30.0, null, 82),
                BandRule.of("GTE", 20.0, null, 68),
                BandRule.of("GTE", 10.0, null, 48),
                BandRule.of("ELSE", null, null, 28))));
        subs.add(SubNode.strategy("height_gather", "高度聚集度", 0.25, "height_gather"));
        subs.add(SubNode.band("amount_gather", "成交额聚集度", 0.20, "amount_gather_pct", ladder(
                BandRule.of("GTE", 40.0, null, 95),
                BandRule.of("GTE", 25.0, null, 80),
                BandRule.of("GTE", 15.0, null, 60),
                BandRule.of("ELSE", null, null, 35))));
        subs.add(SubNode.strategy("catalyst", "催化剂硬度", 0.15, "catalyst"));
        subs.add(SubNode.band("persistence", "持续性", 0.15, "persistence_days", ladder(
                BandRule.of("GTE", 5.0, null, 95),
                BandRule.of("GTE", 3.0, null, 85),
                BandRule.of("GTE", 2.0, null, 70),
                BandRule.of("GTE", 1.0, null, 50),
                BandRule.of("ELSE", null, null, 25))));
        return new DimNode("theme_main", "日内核心", 0.18, 2, "score_theme_main", subs);
    }

    /** 高度聚集度阶梯（仅空间板在主线行业时使用；不在主线走 strategyHeightGather 的归属公式）。 */
    private static final List<BandRule> HEIGHT_GATHER_LADDER = ladder(
            BandRule.of("GTE", 90.0, null, 95),
            BandRule.of("GTE", 70.0, null, 85),
            BandRule.of("GTE", 50.0, null, 70),
            BandRule.of("GTE", 30.0, null, 50),
            BandRule.of("ELSE", null, null, 28));

    /** 催化剂硬度阶梯：硬度 1-5 → 20/40/60/80/100；无题材行的 50 缺省在策略方法里给，不走阶梯。 */
    private static final List<BandRule> CATALYST_LADDER = ladder(
            BandRule.of("GTE", 5.0, null, 100),
            BandRule.of("GTE", 4.0, null, 80),
            BandRule.of("GTE", 3.0, null, 60),
            BandRule.of("GTE", 2.0, null, 40),
            BandRule.of("GTE", 1.0, null, 20),
            BandRule.of("ELSE", null, null, 0));

    private static DimNode boardDim() {
        List<SubNode> subs = new ArrayList<>();
        // 子权重按三层简化 PRD v2.2（2026-09-13）：数量高度10/晋级30/溢价25/大面20/炸板15，和=1.00。
        // 去重：中高位/极高位并成高位(5板+)，只保留接力效率视角且高位权重压低，穿透的高位抱团/监管/反包归 D5。
        // 数量高度仍排最前（用户项4：排序靠前）；权重按方案回调到 10%。
        subs.add(SubNode.band("count_height", "数量高度", 0.10, "max_height", ladder(
                BandRule.of("GTE", 7.0, null, 95),
                BandRule.of("GTE", 5.0, null, 70),
                BandRule.of("GTE", 4.0, null, 25),
                BandRule.of("GTE", 3.0, null, 15),
                BandRule.of("ELSE", null, null, 5))));
        subs.add(SubNode.composite("promo", "晋级结构", 0.30, LAYER_WEIGHTED_BAND, layers(
                promoLayer("promo_low", "低位晋级", 0.50, "jr_low"),
                promoLayer("promo_mid", "中位晋级", 0.35, "jr_mid"),
                promoLayer("promo_high", "高位晋级", 0.15, "jr_high"))));
        subs.add(SubNode.composite("premium", "溢价结构", 0.25, LAYER_WEIGHTED_BAND, layers(
                premiumLayer("premium_low", "低位溢价", 0.45, "prem_low"),
                premiumLayer("premium_mid", "中位溢价", 0.35, "prem_mid"),
                premiumLayer("premium_high", "高位溢价", 0.20, "prem_high"))));
        subs.add(SubNode.composite("bigloss", "大面结构", 0.20, LAYER_WEIGHTED_BAND, layers(
                biglossLayer("bigloss_low", "低位大面", 0.45, "big_low"),
                biglossLayer("bigloss_mid", "中位大面", 0.35, "big_mid"),
                biglossLayer("bigloss_high", "高位大面", 0.20, "big_high"))));
        subs.add(SubNode.composite("broken_quality", "炸板质量", 0.15, WEIGHTED_SUM, layers(
                SubNode.band("bq_sealed", "家数封板率", 0.60, "sealed_home_rate", ladder(
                        BandRule.of("GTE", 85.0, null, 95),
                        BandRule.of("GTE", 70.0, null, 80),
                        BandRule.of("GTE", 55.0, null, 60),
                        BandRule.of("GTE", 45.0, null, 40),
                        BandRule.of("ELSE", null, null, 15))),
                SubNode.band("bq_reseal", "回封率", 0.40, "reseal_rate", ladder(
                        BandRule.of("GTE", 75.0, null, 95),
                        BandRule.of("GTE", 60.0, null, 80),
                        BandRule.of("GTE", 45.0, null, 60),
                        BandRule.of("GTE", 30.0, null, 40),
                        BandRule.of("ELSE", null, null, 15))))));
        return new DimNode("board", "连板生态", 0.22, 3, "score_board", subs);
    }

    private static SubNode promoLayer(String key, String label, double weight, String sourceKey) {
        return SubNode.band(key, label, weight, sourceKey, ladder(
                BandRule.of("GTE", 60.0, null, 95),
                BandRule.of("GTE", 40.0, null, 80),
                BandRule.of("GTE", 25.0, null, 65),
                BandRule.of("GTE", 15.0, null, 45),
                BandRule.of("ELSE", null, null, 20)));
    }

    private static SubNode premiumLayer(String key, String label, double weight, String sourceKey) {
        return SubNode.band(key, label, weight, sourceKey, ladder(
                BandRule.of("GT", 3.0, null, 95),
                BandRule.of("GTE", 1.0, null, 80),
                BandRule.of("GTE", 0.0, null, 65),
                BandRule.of("GTE", -1.0, null, 45),
                BandRule.of("GTE", -3.0, null, 25),
                BandRule.of("ELSE", null, null, 5)));
    }

    /**
     * 大面结构·单层双指标（2026-09-12）：同一层「家数」与「大面率（大面家数/该层昨日种子）」各占 50% 合成。
     * 家数看绝对量、率看相对面（小样本比家数更公平）；任一读数缺失时另一指标独撑整层权重。
     */
    private static SubNode biglossLayer(String key, String label, double weight, String sourceKey) {
        return SubNode.composite(key, label, weight, WEIGHTED_SUM, layers(
                SubNode.band(key + "_cnt", label + "·家数", 0.50, sourceKey, ladder(
                        BandRule.of("EQ", 0.0, null, 95),
                        BandRule.of("LTE", 2.0, null, 80),
                        BandRule.of("LTE", 5.0, null, 60),
                        BandRule.of("LTE", 10.0, null, 35),
                        BandRule.of("ELSE", null, null, 10))),
                SubNode.band(key + "_rate", label + "·大面率", 0.50, sourceKey + "_rate", ladder(
                        BandRule.of("EQ", 0.0, null, 95),
                        BandRule.of("LTE", 10.0, null, 85),
                        BandRule.of("LTE", 20.0, null, 70),
                        BandRule.of("LTE", 35.0, null, 50),
                        BandRule.of("LTE", 50.0, null, 30),
                        BandRule.of("ELSE", null, null, 10)))));
    }

    /**
     * D4 首板生态（时间截面 PRD v2.0，2026-09-12）：<b>纯 T 日</b>，只衡量"今天资金的试错/播种意愿"。
     * 1进2晋级率/首板溢价/1进2大面是 T-1→T 兑现口径，已迁入 D3 连板生态低位层（jr_low/prem_low/big_low）。
     * 五子：首板数量30 / 首板封板率25 / 首板炸板率20 / 封单质量15(均封单0.6+一字占比0.4) / 题材聚集10。
     */
    private static DimNode firstDim() {
        List<SubNode> subs = new ArrayList<>();
        subs.add(SubNode.band("first_count", "首板数量", 0.30, "first_count", ladder(
                BandRule.of("GTE", 60.0, null, 95),
                BandRule.of("GTE", 40.0, null, 80),
                BandRule.of("GTE", 25.0, null, 65),
                BandRule.of("GTE", 15.0, null, 50),
                BandRule.of("GTE", 8.0, null, 35),
                BandRule.of("ELSE", null, null, 20))));
        subs.add(SubNode.band("first_sealed", "首板封板率", 0.25, "first_sealed_rate", ladder(
                BandRule.of("GTE", 80.0, null, 95),
                BandRule.of("GTE", 70.0, null, 80),
                BandRule.of("GTE", 60.0, null, 65),
                BandRule.of("GTE", 50.0, null, 50),
                BandRule.of("ELSE", null, null, 30))));
        // 首板炸板率=首板炸板/(封住+炸板)，越低越好（与封板率互补但阶梯非对称）
        subs.add(SubNode.band("first_bomb", "首板炸板率", 0.20, "first_bomb_rate", ladder(
                BandRule.of("LTE", 10.0, null, 95),
                BandRule.of("LTE", 20.0, null, 80),
                BandRule.of("LTE", 30.0, null, 60),
                BandRule.of("LTE", 40.0, null, 40),
                BandRule.of("ELSE", null, null, 20))));
        // 封单质量：0.6×首板均封单额分 + 0.4×一字首板占比分（亿元 / %）
        subs.add(SubNode.composite("first_seal_quality", "封单质量", 0.15, WEIGHTED_SUM, layers(
                SubNode.band("first_avg_seal", "首板均封单", 0.60, "first_avg_seal_amount", ladder(
                        BandRule.of("GTE", 3.0, null, 95),
                        BandRule.of("GTE", 1.5, null, 80),
                        BandRule.of("GTE", 0.8, null, 60),
                        BandRule.of("GTE", 0.4, null, 40),
                        BandRule.of("ELSE", null, null, 20))),
                SubNode.band("first_yizi", "一字首板占比", 0.40, "first_yizi_ratio", ladder(
                        BandRule.of("GTE", 30.0, null, 95),
                        BandRule.of("GTE", 20.0, null, 80),
                        BandRule.of("GTE", 10.0, null, 60),
                        BandRule.of("GTE", 5.0, null, 40),
                        BandRule.of("ELSE", null, null, 20))))));
        // 题材健康度：首板在最热行业的聚集度（只数 T 日新首板，区别于全涨停口径的 zt_gather_pct）
        subs.add(SubNode.band("first_theme", "首板题材聚集", 0.10, "first_theme_gather_pct", ladder(
                BandRule.of("GTE", 40.0, null, 95),
                BandRule.of("GTE", 30.0, null, 82),
                BandRule.of("GTE", 20.0, null, 68),
                BandRule.of("GTE", 10.0, null, 48),
                BandRule.of("ELSE", null, null, 28))));
        return new DimNode("first", "首板生态", 0.13, 4, "score_first", subs);
    }

    /**
     * D5 高位生态（阵眼·抱团·监管，2026-09-12 融合版，权重 0.25）：
     * 阵眼个体 35%（人工 t_anchor：行为40/高度25/封板20/主线一致15，取数层算 0-100 走 MANUAL）
     * + 抱团与资金 30%（结构60：高位家数占比/封单集中/空间唯一/梯队；强度40：高位溢价/高位晋级）
     * + 监管压制 20%（家数/高位占比/扩散）+ 监管反馈 15%（STRATEGY 五态）。
     * 无在位阵眼时阵眼子整支未评；事件窗无公告时压制/反馈未评——引擎按已评权重归一，不兜 0。
     */
    private static DimNode highEcoDim() {
        List<SubNode> subs = new ArrayList<>();
        // ① 阵眼个体 35%
        subs.add(SubNode.composite("anchor_ind", "阵眼个体", 0.35, WEIGHTED_SUM, layers(
                SubNode.manual("d5a_action", "龙头行为", 0.40, "d5a_action"),
                SubNode.manual("d5a_height", "龙头高度", 0.25, "d5a_height"),
                SubNode.manual("d5a_seal", "封板质量", 0.20, "d5a_seal"),
                SubNode.manual("d5a_consist", "主线一致性", 0.15, "d5a_consist"))));
        // ② 抱团与资金 30%：结构 60% + 强度 40%
        subs.add(SubNode.composite("coalition", "抱团与资金", 0.30, WEIGHTED_SUM, layers(
                SubNode.composite("c_structure", "结构质量", 0.60, WEIGHTED_SUM, layers(
                        SubNode.band("d5c_ratio", "高位家数占比", 0.30, "d5c_ratio", D5_RATIO_LADDER),
                        SubNode.band("d5c_seal", "高位封单集中", 0.25, "d5c_seal", D5_SEAL_RATIO_LADDER),
                        SubNode.band("d5c_top", "空间板唯一性", 0.25, "d5c_top", D5_TOP_LADDER),
                        SubNode.manual("d5c_tier", "梯队支撑度", 0.20, "d5c_tier"))),
                SubNode.composite("c_strength", "资金强度", 0.40, WEIGHTED_SUM, layers(
                        SubNode.band("d5c_prem", "高位溢价", 0.50, "d5c_prem", D5_PREM_LADDER),
                        SubNode.band("d5c_jr", "高位晋级率", 0.50, "d5c_jr", D5_JR_LADDER))))));
        // ③ 监管压制 20%
        subs.add(SubNode.composite("pressure", "监管压制", 0.20, WEIGHTED_SUM, layers(
                SubNode.band("d5p_count", "监管家数", 0.40, "d5p_count", D5_P_COUNT_LADDER),
                SubNode.band("d5p_high_ratio", "高位监管占比", 0.35, "d5p_high_ratio", D5_P_HIGH_RATIO_LADDER),
                SubNode.band("d5p_spread", "监管扩散度", 0.25, "d5p_spread", D5_P_SPREAD_LADDER))));
        // ④ 监管反馈 15%
        subs.add(SubNode.strategy("feedback", "监管反馈", 0.15, "d5_feedback"));
        return new DimNode("high", "高位生态", 0.25, 5, "score_high", subs);
    }

    /** 高位家数占比（高位/连板≥2，%）：20-40 健康 100；10-20 与 40-60 给 70；>60 过度抱团 40；<10 无抱团 50。 */
    private static final List<BandRule> D5_RATIO_LADDER = ladder(
            BandRule.of("BETWEEN", 20.0, 40.0, 100),
            BandRule.of("GT", 60.0, null, 40),
            BandRule.of("GTE", 40.0, null, 70),
            BandRule.of("GTE", 10.0, null, 70),
            BandRule.of("ELSE", null, null, 50));

    /** 高位封单集中度（%）：≤50=90；≤70=70；>70=40。 */
    private static final List<BandRule> D5_SEAL_RATIO_LADDER = ladder(
            BandRule.of("LTE", 50.0, null, 90),
            BandRule.of("LTE", 70.0, null, 70),
            BandRule.of("ELSE", null, null, 40));

    /** 空间板唯一性（家数）：2-3 互相支撑 100；≥4 分散 70；唯一孤军 50。 */
    private static final List<BandRule> D5_TOP_LADDER = ladder(
            BandRule.of("BETWEEN", 2.0, 3.0, 100),
            BandRule.of("GTE", 4.0, null, 70),
            BandRule.of("ELSE", null, null, 50));

    /** 高位溢价（%）：>3=95；1-3=80；0-1=65；-3~0=35；<-3=10。 */
    private static final List<BandRule> D5_PREM_LADDER = ladder(
            BandRule.of("GT", 3.0, null, 95),
            BandRule.of("GTE", 1.0, null, 80),
            BandRule.of("GTE", 0.0, null, 65),
            BandRule.of("GTE", -3.0, null, 35),
            BandRule.of("ELSE", null, null, 10));

    /** 高位晋级率（%）：≥60=95；40-60=80；25-40=65；15-25=45；<15=20。 */
    private static final List<BandRule> D5_JR_LADDER = ladder(
            BandRule.of("GTE", 60.0, null, 95),
            BandRule.of("GTE", 40.0, null, 80),
            BandRule.of("GTE", 25.0, null, 65),
            BandRule.of("GTE", 15.0, null, 45),
            BandRule.of("ELSE", null, null, 20));

    /** 监管家数：0=100；≤2=80；≤5=55；≤10=30；>10=10。 */
    private static final List<BandRule> D5_P_COUNT_LADDER = ladder(
            BandRule.of("EQ", 0.0, null, 100),
            BandRule.of("LTE", 2.0, null, 80),
            BandRule.of("LTE", 5.0, null, 55),
            BandRule.of("LTE", 10.0, null, 30),
            BandRule.of("ELSE", null, null, 10));

    /** 高位监管占比（%）：0=100；≤30=70；≤60=40；>60=15。 */
    private static final List<BandRule> D5_P_HIGH_RATIO_LADDER = ladder(
            BandRule.of("EQ", 0.0, null, 100),
            BandRule.of("LTE", 30.0, null, 70),
            BandRule.of("LTE", 60.0, null, 40),
            BandRule.of("ELSE", null, null, 15));

    /** 监管扩散度（同行业最多家数）：0=100；1=85；≤3=50；>3=20。 */
    private static final List<BandRule> D5_P_SPREAD_LADDER = ladder(
            BandRule.of("EQ", 0.0, null, 100),
            BandRule.of("LTE", 1.0, null, 85),
            BandRule.of("LTE", 3.0, null, 50),
            BandRule.of("ELSE", null, null, 20));

    private static List<BandRule> ladder(BandRule... rules) {
        List<BandRule> list = new ArrayList<>();
        for (BandRule r : rules) {
            list.add(r);
        }
        return list;
    }

    private static List<SubNode> layers(SubNode... children) {
        List<SubNode> list = new ArrayList<>();
        for (SubNode s : children) {
            list.add(s);
        }
        return list;
    }
}
