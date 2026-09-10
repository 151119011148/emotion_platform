package com.emotion.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 五维双层 0-100 情绪打分引擎（纯函数，不碰 DB）。
 *
 * 输入是一棵 {@link ScoringTree} 配置树快照 + 一张 {@code metrics}（所有自动/人工原始读数，键=source_key）。
 * 引擎按节点的 scoring_kind 递归求值：维/复合子加权合成、BAND_LADDER 走阈值阶梯、STRATEGY 调 Java 命名算法、
 * MANUAL 直接夹 0-100。维分/子分/层分一律 0-100，未评（缺读数）从分母剔除不兜 0，总分=Σ(维分×维权)（全维评了即直加权和）。
 * 求完连板维后按结构信号「中位吹哨」施加 ×0.8；再判 5 个结构信号、4 条强制退潮；最后落 4 个交易纪律带。
 *
 * {@link #builtinTree()} 与 schema.sql 的 five_dim 种子逐字对齐，由 ScoringModelSeedParityTest 钉住（R1：改引擎常量或种子必须跑它）。
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

    // 连板「中位吹哨」命中时整维乘数（对齐 schema t_scoring_rule GUARD promo 行 note）
    public static final BigDecimal BOARD_WHISTLE_MULTIPLIER = new BigDecimal("0.8");

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

    // ---- 不可约策略的展示常量（与 schema COMPOUND 行一致，ParityTest 会比对）----
    public static final BigDecimal INDEX_ENV_FULL_UP = new BigDecimal("100");
    public static final BigDecimal INDEX_ENV_TWO_DOWN = new BigDecimal("40");
    public static final BigDecimal INDEX_ENV_ALL_DOWN = new BigDecimal("20");
    public static final BigDecimal INDEX_ENV_MID = new BigDecimal("60");
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
        private List<NodeEval> children = new ArrayList<>();
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

        Result r = new Result();
        BigDecimal num = BigDecimal.ZERO;
        BigDecimal den = BigDecimal.ZERO;
        for (DimNode dim : t.getDims()) {
            NodeEval de = evalDim(dim, m, whistle);
            r.dimEvals.add(de);
            BigDecimal score = de.getScore();
            if (score == null) {
                continue; // 整维未评：剔出分母
            }
            r.dimScores.put(dim.getDimKey(), score);
            BigDecimal w = BigDecimal.valueOf(dim.getWeight());
            num = num.add(w.multiply(score));
            den = den.add(w);
        }
        if (den.signum() > 0) {
            r.total = clamp0to100(num.divide(den, 6, RoundingMode.HALF_UP)).setScale(2, RoundingMode.HALF_UP);
        }
        r.signalFlags = signals;

        ForcedEbb fe = detectForcedEbb(m);
        r.forcedEbb = fe.forced;
        r.forcedEbbReason = fe.reason;
        r.stage = stageOf(r.total, r.forcedEbb);
        return r;
    }

    /** 一维完整 eval（含直属 subs 与层）。score 已 clamp 0-100 + HALF_UP 2 位；未评=null。
     *  board 维命中中位吹哨时 score ×0.8 并挂 note，与旧路径完全等价。 */
    private static NodeEval evalDim(DimNode dim, Map<String, BigDecimal> m, boolean whistle) {
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
        if ("board".equals(dim.getDimKey()) && whistle) {
            raw = raw.multiply(BOARD_WHISTLE_MULTIPLIER);
            de.setNote("中位吹哨：本维 ×0.8");
        }
        de.setScore(clamp0to100(raw).setScale(2, RoundingMode.HALF_UP));
        return de;
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
            case "limit_combo":
                return strategyLimitCombo(m);
            case "board_anchor":
                return strategyBoardAnchor(m);
            default:
                return null;
        }
    }

    /** 指数环境：三指涨跌幅(百分点)及协同性。缺任一指数=未评。 */
    static BigDecimal strategyIndexEnv(Map<String, BigDecimal> m) {
        BigDecimal a = m.get("index1_pct");
        BigDecimal b = m.get("index2_pct");
        BigDecimal c = m.get("index3_pct");
        if (a == null || b == null || c == null) {
            return null;
        }
        BigDecimal one = BigDecimal.ONE;
        BigDecimal negOne = BigDecimal.ONE.negate();
        if (a.compareTo(one) > 0 && b.compareTo(one) > 0 && c.compareTo(one) > 0) {
            return INDEX_ENV_FULL_UP;   // 三指均涨 >1%
        }
        if (a.compareTo(negOne) < 0 && b.compareTo(negOne) < 0 && c.compareTo(negOne) < 0) {
            return INDEX_ENV_ALL_DOWN;  // 三指跌 >1%(均<-1%)
        }
        int down = 0;
        int up = 0;
        for (BigDecimal v : new BigDecimal[] { a, b, c }) {
            if (v.signum() < 0) {
                down++;
            } else if (v.signum() > 0) {
                up++;
            }
        }
        if (down == 2 && up == 1) {
            return INDEX_ENV_TWO_DOWN;  // 两跌一红
        }
        return INDEX_ENV_MID;           // 其余混合/微动
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

    private static boolean isOne(BigDecimal v) {
        return v != null && v.signum() != 0;
    }

    /** 5 个结构信号（可多选）；只在该信号所需读数齐备时才可能命中。 */
    static List<String> detectSignals(Map<String, BigDecimal> m) {
        List<String> out = new ArrayList<>();
        BigDecimal jrLow = m.get("jr_low");
        BigDecimal jrMid = m.get("jr_mid");
        BigDecimal jrMidHigh = m.get("jr_midhigh");
        BigDecimal jrTop = m.get("jr_top");
        BigDecimal premMid = m.get("prem_mid");
        BigDecimal bigMid = m.get("big_mid");

        boolean whistle = (jrMid != null && jrMid.compareTo(new BigDecimal("15")) < 0)
                || (premMid != null && bigMid != null
                        && premMid.signum() < 0 && bigMid.compareTo(new BigDecimal("3")) >= 0);
        if (whistle) {
            out.add(SIG_WHISTLE);
        }
        if (jrTop != null && jrMid != null && jrLow != null
                && jrTop.compareTo(new BigDecimal("50")) >= 0
                && jrMid.compareTo(new BigDecimal("25")) < 0
                && jrLow.compareTo(new BigDecimal("25")) < 0) {
            out.add(SIG_TOP_CROWD);
        }
        if (jrTop != null && jrMidHigh != null
                && jrTop.compareTo(new BigDecimal("50")) >= 0
                && jrMidHigh.compareTo(new BigDecimal("20")) < 0) {
            out.add(SIG_CROWD_COLLAPSE);
        }
        if (jrLow != null && jrTop != null
                && jrLow.compareTo(new BigDecimal("40")) >= 0
                && jrTop.compareTo(new BigDecimal("30")) < 0) {
            out.add(SIG_HIGH_LOW_SWITCH);
        }
        if (jrLow != null && jrMid != null && jrTop != null
                && jrLow.compareTo(new BigDecimal("15")) < 0
                && jrMid.compareTo(new BigDecimal("15")) < 0
                && jrTop.compareTo(new BigDecimal("20")) < 0) {
            out.add(SIG_FULL_EBB);
        }
        return out;
    }

    static final class ForcedEbb {
        boolean forced;
        String reason;
    }

    /** 4 条强制退潮任一触发（无视总分）：1 跌停>=10；2 阵眼跌停/核按钮；3 中位晋级<10%且中位大面>=5；4 极高位爆量断板。 */
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
        dims.add(anchorDim());
        return new ScoringTree("five_dim", "五维双层情绪模型", 100.0, dims);
    }

    private static DimNode marketDim() {
        List<SubNode> subs = new ArrayList<>();
        subs.add(SubNode.strategy("index_env", "指数环境", 0.35, "index_env"));
        subs.add(SubNode.band("turnover", "量能", 0.25, "turnover_ratio", ladder(
                BandRule.of("GTE", 1.20, null, 90),
                BandRule.of("GTE", 0.95, null, 70),
                BandRule.of("GTE", 0.70, null, 45),
                BandRule.of("ELSE", null, null, 25))));
        subs.add(SubNode.band("breadth", "广度", 0.20, "red_ratio", ladder(
                BandRule.of("GTE", 0.60, null, 85),
                BandRule.of("GTE", 0.40, null, 55),
                BandRule.of("GTE", 0.20, null, 30),
                BandRule.of("ELSE", null, null, 10))));
        subs.add(SubNode.strategy("limit_combo", "涨跌停", 0.20, "limit_combo"));
        return new DimNode("market", "大盘生态", 0.25, 1, "score_market", subs);
    }

    private static DimNode themeMainDim() {
        List<SubNode> subs = new ArrayList<>();
        subs.add(SubNode.band("sector_limit_up", "板块涨停数", 0.30, "sector_limit_up_count", ladder(
                BandRule.of("GTE", 15.0, null, 90),
                BandRule.of("GTE", 10.0, null, 78),
                BandRule.of("GTE", 6.0, null, 65),
                BandRule.of("GTE", 3.0, null, 45),
                BandRule.of("ELSE", null, null, 30))));
        subs.add(SubNode.manual("ladder_complete", "梯队完整性", 0.30, "ladder_complete_score"));
        subs.add(SubNode.band("sector_premium", "板块溢价", 0.25, "sector_premium_pct", ladder(
                BandRule.of("GT", 3.0, null, 90),
                BandRule.of("GTE", 0.0, null, 55),
                BandRule.of("ELSE", null, null, 20))));
        subs.add(SubNode.band("persistence", "持续性", 0.15, "persistence_days", ladder(
                BandRule.of("GTE", 3.0, null, 85),
                BandRule.of("GTE", 2.0, null, 68),
                BandRule.of("EQ", 1.0, null, 50))));
        return new DimNode("theme_main", "主线明确度", 0.20, 2, "score_theme_main", subs);
    }

    private static DimNode boardDim() {
        List<SubNode> subs = new ArrayList<>();
        subs.add(SubNode.composite("promo", "晋级结构", 0.25, LAYER_WEIGHTED_BAND, layers(
                promoLayer("promo_low", "低位晋级", 0.15, "jr_low"),
                promoLayer("promo_mid", "中位晋级", 0.25, "jr_mid"),
                promoLayer("promo_midhigh", "中高位晋级", 0.20, "jr_midhigh"),
                promoLayer("promo_top", "极高位晋级", 0.40, "jr_top"))));
        subs.add(SubNode.composite("premium", "溢价结构", 0.20, LAYER_WEIGHTED_BAND, layers(
                premiumLayer("premium_low", "低位溢价", 0.15, "prem_low"),
                premiumLayer("premium_mid", "中位溢价", 0.25, "prem_mid"),
                premiumLayer("premium_midhigh", "中高位溢价", 0.20, "prem_midhigh"),
                premiumLayer("premium_top", "极高位溢价", 0.40, "prem_top"))));
        subs.add(SubNode.composite("bigloss", "大面结构", 0.20, LAYER_WEIGHTED_BAND, layers(
                biglossLayer("bigloss_low", "低位大面", 0.15, "big_low"),
                biglossLayer("bigloss_mid", "中位大面", 0.25, "big_mid"),
                biglossLayer("bigloss_midhigh", "中高位大面", 0.20, "big_midhigh"),
                biglossLayer("bigloss_top", "极高位大面", 0.40, "big_top"))));
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
        subs.add(SubNode.band("count_height", "数量高度", 0.10, "board_total_count", ladder(
                BandRule.of("GTE", 25.0, null, 95),
                BandRule.of("GTE", 15.0, null, 80),
                BandRule.of("GTE", 8.0, null, 60),
                BandRule.of("GTE", 4.0, null, 40),
                BandRule.of("ELSE", null, null, 20))));
        return new DimNode("board", "连板生态", 0.25, 3, "score_board", subs);
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

    private static SubNode biglossLayer(String key, String label, double weight, String sourceKey) {
        return SubNode.band(key, label, weight, sourceKey, ladder(
                BandRule.of("EQ", 0.0, null, 95),
                BandRule.of("LTE", 2.0, null, 80),
                BandRule.of("LTE", 5.0, null, 60),
                BandRule.of("LTE", 10.0, null, 35),
                BandRule.of("ELSE", null, null, 10)));
    }

    private static DimNode firstDim() {
        List<SubNode> subs = new ArrayList<>();
        subs.add(SubNode.band("first_count", "首板数量", 0.25, "first_count", ladder(
                BandRule.of("GTE", 60.0, null, 95),
                BandRule.of("GTE", 40.0, null, 80),
                BandRule.of("GTE", 25.0, null, 60),
                BandRule.of("GTE", 10.0, null, 40),
                BandRule.of("ELSE", null, null, 15))));
        subs.add(SubNode.band("first_sealed", "首板封板率", 0.15, "first_sealed_rate", ladder(
                BandRule.of("GTE", 80.0, null, 95),
                BandRule.of("GTE", 65.0, null, 80),
                BandRule.of("GTE", 50.0, null, 60),
                BandRule.of("GTE", 40.0, null, 40),
                BandRule.of("ELSE", null, null, 15))));
        subs.add(SubNode.band("first_premium", "首板溢价", 0.25, "first_premium_pct", ladder(
                BandRule.of("GT", 3.0, null, 95),
                BandRule.of("GTE", 1.0, null, 75),
                BandRule.of("GTE", -1.0, null, 50),
                BandRule.of("ELSE", null, null, 25))));
        subs.add(SubNode.band("promo_1to2", "1进2晋级", 0.25, "first_promo_1to2_rate", ladder(
                BandRule.of("GTE", 25.0, null, 95),
                BandRule.of("GTE", 15.0, null, 75),
                BandRule.of("GTE", 5.0, null, 45),
                BandRule.of("ELSE", null, null, 20))));
        subs.add(SubNode.band("big_1to2", "1进2大面", 0.10, "first_1to2_big_count", ladder(
                BandRule.of("EQ", 0.0, null, 95),
                BandRule.of("LTE", 3.0, null, 75),
                BandRule.of("LTE", 6.0, null, 45),
                BandRule.of("LTE", 10.0, null, 25),
                BandRule.of("ELSE", null, null, 10))));
        return new DimNode("first", "首板生态", 0.15, 4, "score_first", subs);
    }

    private static DimNode anchorDim() {
        List<SubNode> subs = new ArrayList<>();
        subs.add(SubNode.strategy("core", "空间板/核心龙", 1.00, "board_anchor"));
        return new DimNode("anchor", "阵眼", 0.15, 5, "score_anchor", subs);
    }

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
