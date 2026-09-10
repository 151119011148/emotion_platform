package com.emotion.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.emotion.util.BoardScoreCalculator.NodeEval;
import com.emotion.util.BoardScoreCalculator.Result;

/**
 * 五维双层引擎口径。重点覆盖：每一档都能被算到、缺读数=未评(不兜 0、剔出分母)、
 * 中位吹哨 ×0.8、四层加权、策略分支、结构信号、强制退潮 4 条、交易纪律 4 带。
 */
class BoardScoreCalculatorTest {

    /** 交替 key/value(String 或 Number) 构造 metrics；value 转 BigDecimal。 */
    private static Map<String, BigDecimal> m(Object... kv) {
        Map<String, BigDecimal> map = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v != null) {
                map.put((String) kv[i], v instanceof BigDecimal ? (BigDecimal) v : new BigDecimal(v.toString()));
            }
        }
        return map;
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertTrue(actual != null, "value was null, expected " + expected);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static SubNode byKey(List<SubNode> subs, String key) {
        for (SubNode s : subs) {
            if (key.equals(s.getSubKey())) {
                return s;
            }
        }
        throw new AssertionError("no sub " + key);
    }

    // ---------------------------------------------------------------- 内置树结构 = 种子

    @Test
    void builtinTree_structure() {
        ScoringTree t = BoardScoreCalculator.builtinTree();
        assertEquals("five_dim_v2", t.getModelKey());
        assertEquals(100.0, t.getMaxScore(), 1e-9);
        assertEquals(5, t.getDims().size());

        double dimWeightSum = 0;
        for (DimNode d : t.getDims()) {
            dimWeightSum += d.getWeight();
        }
        assertEquals(1.0, dimWeightSum, 1e-9);

        DimNode market = dim(t, "market");
        assertEquals(4, market.getSubs().size());
        assertEquals(1.0, weightSum(market.getSubs()), 1e-9);

        // PRD 2.0：D2 五要素（涨停/高度/成交额聚集度 + 催化剂硬度 + 持续性）
        DimNode theme = dim(t, "theme_main");
        assertEquals(5, theme.getSubs().size());
        assertEquals("zt_gather_pct", theme.getSubs().get(0).getSourceKey());
        assertEquals("catalyst_hardness", theme.getSubs().get(3).getSourceKey());
        assertEquals(1.0, weightSum(theme.getSubs()), 1e-9);

        DimNode board = dim(t, "board");
        assertEquals(5, board.getSubs().size());
        SubNode promo = byKey(board.getSubs(), "promo");
        assertEquals("LAYER_WEIGHTED_BAND", promo.getScoringKind());
        assertEquals(4, promo.getChildren().size());
        assertEquals(1.0, weightSum(promo.getChildren()), 1e-9);
        assertEquals("jr_low", promo.getChildren().get(0).getSourceKey());
        assertEquals("jr_top", promo.getChildren().get(3).getSourceKey());

        // PRD 2.0：D5 龙头分工（总龙头50/中军20/跟风15/卡位10/反包5）
        DimNode anchor = dim(t, "anchor");
        assertEquals(5, anchor.getSubs().size());
        assertEquals(1.0, weightSum(anchor.getSubs()), 1e-9);
        SubNode zong = byKey(anchor.getSubs(), "dragon_zong_long");
        assertEquals("MANUAL", zong.getScoringKind());
        assertEquals("dragon_zong_long", zong.getSourceKey());
        assertEquals(0.5, zong.getWeight(), 1e-9);
        assertEquals("dragon_fan_bao", anchor.getSubs().get(4).getSourceKey());
    }

    private static DimNode dim(ScoringTree t, String key) {
        for (DimNode d : t.getDims()) {
            if (key.equals(d.getDimKey())) {
                return d;
            }
        }
        throw new AssertionError("no dim " + key);
    }

    private static double weightSum(List<SubNode> subs) {
        double s = 0;
        for (SubNode n : subs) {
            s += n.getWeight();
        }
        return s;
    }

    // ---------------------------------------------------------------- 策略分支

    @Test
    void strategyIndexEnv() {
        assertEquals(0, BoardScoreCalculator.strategyIndexEnv(m("index1_pct", 1.5, "index2_pct", 2.0, "index3_pct", 1.2))
                .compareTo(BoardScoreCalculator.INDEX_ENV_FULL_UP));
        assertEquals(0, BoardScoreCalculator.strategyIndexEnv(m("index1_pct", -2.0, "index2_pct", -1.5, "index3_pct", -1.2))
                .compareTo(BoardScoreCalculator.INDEX_ENV_ALL_DOWN));
        assertEquals(0, BoardScoreCalculator.strategyIndexEnv(m("index1_pct", -1.0, "index2_pct", -0.5, "index3_pct", 0.8))
                .compareTo(BoardScoreCalculator.INDEX_ENV_TWO_DOWN));
        assertEquals(0, BoardScoreCalculator.strategyIndexEnv(m("index1_pct", 0.2, "index2_pct", -0.1, "index3_pct", 0.3))
                .compareTo(BoardScoreCalculator.INDEX_ENV_MID));
        assertNull(BoardScoreCalculator.strategyIndexEnv(m("index1_pct", 1.5, "index2_pct", 2.0)));
    }

    @Test
    void strategyLimitCombo() {
        assertEquals(0, BoardScoreCalculator.strategyLimitCombo(m("limit_up_count", 90, "limit_down_count", 0))
                .compareTo(BoardScoreCalculator.LIMIT_COMBO_STRONG));
        assertEquals(0, BoardScoreCalculator.strategyLimitCombo(m("limit_up_count", 50, "limit_down_count", 6))
                .compareTo(BoardScoreCalculator.LIMIT_COMBO_MIXED));
        assertEquals(0, BoardScoreCalculator.strategyLimitCombo(m("limit_up_count", 30, "limit_down_count", 25))
                .compareTo(BoardScoreCalculator.LIMIT_COMBO_CRASH));
        assertEquals(0, BoardScoreCalculator.strategyLimitCombo(m("limit_up_count", 50, "limit_down_count", 1))
                .compareTo(BoardScoreCalculator.LIMIT_COMBO_MID));
        assertNull(BoardScoreCalculator.strategyLimitCombo(m("limit_up_count", 50)));
    }

    @Test
    void strategyBoardAnchor() {
        assertEquals(0, BoardScoreCalculator.strategyBoardAnchor(m("anchor_limit_down", 1, "anchor_broke", 0, "anchor_sealed", 0))
                .compareTo(BoardScoreCalculator.ANCHOR_CORE_BUTTON));
        assertEquals(0, BoardScoreCalculator.strategyBoardAnchor(m("anchor_broke", 1, "anchor_sealed", 0))
                .compareTo(BoardScoreCalculator.ANCHOR_BROKE));
        assertEquals(0, BoardScoreCalculator.strategyBoardAnchor(m("anchor_sealed", 1))
                .compareTo(BoardScoreCalculator.ANCHOR_SEALED));
        // 监管折扣：95 × 0.8 = 76
        assertEquals(0, BoardScoreCalculator.strategyBoardAnchor(m("anchor_sealed", 1, "anchor_supervision_discount", 0.8))
                .compareTo(new BigDecimal("76")));
        assertNull(BoardScoreCalculator.strategyBoardAnchor(m("limit_up_count", 50)));
    }

    // ---------------------------------------------------------------- 求值：直加权和 / 未评剔分母 / MANUAL 夹取

    @Test
    void boardFull_noWhistle_directSum() {
        Map<String, BigDecimal> metrics = boardBase("70");
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        // promo=95（四层全 70→GTE60）；premium=95（全 5→GT3）；bigloss=95（全 0→EQ0）；
        // bq=95（90→GTE85、80→GTE75）；count=95（30→GTE25）→ 五子全 95 → board=95.00
        assertAmount("95.00", r.getDimScores().get("board"));
        assertAmount("95.00", r.getTotal());
        assertEquals(BoardScoreCalculator.STAGE_CLIMAX, r.getStage());
        assertTrue(r.getSignalFlags().isEmpty());
        assertFalse(r.isForcedEbb());
    }

    @Test
    void boardWhistle_appliesMultiplier() {
        Map<String, BigDecimal> metrics = boardBase("10"); // 中位晋级 10% → 中位吹哨 → 连板维 ×0.8
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        // promo = 0.15*95 + 0.25*20(jr_mid=10→ELSE) + 0.20*95 + 0.40*95 = 76.25
        // board num = .25*76.25 + .65*95 = 80.8125；五子权和=0.90 → raw=80.8125/0.90=89.7917；×0.8 = 71.83
        assertAmount("71.83", r.getDimScores().get("board"));
        assertAmount("71.83", r.getTotal());
        assertTrue(r.getSignalFlags().contains(BoardScoreCalculator.SIG_WHISTLE));
        assertEquals(BoardScoreCalculator.STAGE_FERMENT, r.getStage());
        assertFalse(r.isForcedEbb());
    }

    /** 连板五子全给分（其余四维留空 → 总分=连板维）。promoMid 决定中位晋级率读数。 */
    private static Map<String, BigDecimal> boardBase(String promoMid) {
        return m(
                "jr_low", 70, "jr_mid", promoMid, "jr_midhigh", 70, "jr_top", 70,
                "prem_low", 5, "prem_mid", 5, "prem_midhigh", 5, "prem_top", 5,
                "big_low", 0, "big_mid", 0, "big_midhigh", 0, "big_top", 0,
                "sealed_home_rate", 90, "reseal_rate", 80,
                "board_total_count", 30);
    }

    @Test
    void manual_isClamped0to100() {
        Map<String, BigDecimal> metrics = m("dragon_zong_long", 150); // 越界人工分
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        assertAmount("100", r.getDimScores().get("anchor")); // 唯一可评子 → 维分=100
        assertAmount("100", r.getTotal());
    }

    @Test
    void v2LaddersEndWithElse_missingKeyStillUnscored() {
        // v2 每条 BAND_LADDER 都有 ELSE 兜底档：0 值照样命中 ELSE 出分（持续性 0 → 25），
        // "未评"只能来自缺键——缺键时整维不出分、total/stage 都不落，绝不兜 0。
        Map<String, BigDecimal> metrics = m("persistence_days", 0);
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        assertAmount("25", r.getDimScores().get("theme_main"));
        assertAmount("25", r.getTotal());

        Result none = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(),
                new TreeMap<String, BigDecimal>());
        assertTrue(none.getDimScores().isEmpty());
        assertNull(none.getTotal());
        assertNull(none.getStage());
    }

    @Test
    void allDimsScored_totalIsDirectWeightedSum() {
        Map<String, BigDecimal> metrics = boardBase("70");
        metrics.putAll(m("index1_pct", 1.5, "index2_pct", 1.5, "index3_pct", 1.5,
                "turnover_ratio", 1.3, "red_ratio", 0.7, "limit_up_count", 90, "limit_down_count", 0,
                "zt_gather_pct", 40, "height_gather_pct", 90, "amount_gather_pct", 40,
                "catalyst_hardness", 5, "persistence_days", 5,
                "first_count", 60, "first_sealed_rate", 85, "first_premium_pct", 4, "first_promo_1to2_rate", 30,
                "first_1to2_big_count", 0,
                "dragon_zong_long", 95, "dragon_zhong_jun", 95, "dragon_gen_feng", 95,
                "dragon_ka_wei", 95, "dragon_fan_bao", 95));
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        // market: 100*.35 + 90*.25 + 85*.20 + 95*.20 = 93.50
        assertAmount("93.50", r.getDimScores().get("market"));
        // theme: 95*.25 + 95*.25 + 95*.20 + 100*.15 + 95*.15 = 95.75
        assertAmount("95.75", r.getDimScores().get("theme_main"));
        assertAmount("95.00", r.getDimScores().get("board"));
        assertAmount("95.00", r.getDimScores().get("first"));
        assertAmount("95.00", r.getDimScores().get("anchor"));
        // 总分（先按维四舍五入再加权，分母=1）：23.375 + 19.15 + 23.75 + 14.25 + 14.25 = 94.775 → 94.78
        assertAmount("94.78", r.getTotal());
        assertEquals(BoardScoreCalculator.STAGE_CLIMAX, r.getStage());
    }

    // ---------------------------------------------------------------- 结构信号

    @Test
    void signals() {
        assertTrue(BoardScoreCalculator.detectSignals(m("prem_mid", -1, "big_mid", 4)).contains(BoardScoreCalculator.SIG_WHISTLE));
        assertTrue(BoardScoreCalculator.detectSignals(m("jr_top", 60, "jr_mid", 20, "jr_low", 20)).contains(BoardScoreCalculator.SIG_TOP_CROWD));
        assertTrue(BoardScoreCalculator.detectSignals(m("jr_top", 60, "jr_midhigh", 10)).contains(BoardScoreCalculator.SIG_CROWD_COLLAPSE));
        assertTrue(BoardScoreCalculator.detectSignals(m("jr_low", 50, "jr_top", 10)).contains(BoardScoreCalculator.SIG_HIGH_LOW_SWITCH));
        List<String> ebb = BoardScoreCalculator.detectSignals(m("jr_low", 10, "jr_mid", 10, "jr_top", 10));
        assertTrue(ebb.contains(BoardScoreCalculator.SIG_FULL_EBB));
        assertTrue(ebb.contains(BoardScoreCalculator.SIG_WHISTLE));
        assertTrue(BoardScoreCalculator.detectSignals(m("jr_low", 50, "jr_mid", 50, "jr_top", 50)).isEmpty());
    }

    // ---------------------------------------------------------------- 强制退潮 4 条

    @Test
    void forcedEbb_conditions() {
        assertTrue(BoardScoreCalculator.detectForcedEbb(m("limit_down_count", 12)).forced);
        assertTrue(BoardScoreCalculator.detectForcedEbb(m("anchor_limit_down", 1)).forced);
        assertTrue(BoardScoreCalculator.detectForcedEbb(m("jr_mid", 5, "big_mid", 6)).forced);
        assertTrue(BoardScoreCalculator.detectForcedEbb(m("max_height", 8, "top_high_turnover_pct", 40, "top_high_break", 1)).forced);
        assertFalse(BoardScoreCalculator.detectForcedEbb(m("limit_down_count", 3, "jr_mid", 30, "big_mid", 1)).forced);
    }

    @Test
    void evaluate_forcedEbb_overridesStage() {
        Map<String, BigDecimal> metrics = boardBase("70"); // 连板 95 → 若不强制退潮本应「高潮」
        metrics.put("limit_down_count", new BigDecimal("20"));               // 触发强制退潮(1)
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        assertTrue(r.isForcedEbb());
        assertTrue(r.getForcedEbbReason() != null);
        assertEquals(BoardScoreCalculator.STAGE_FORCED_EBB, r.getStage());
    }

    // ---------------------------------------------------------------- 交易纪律 4 带

    @Test
    void stageBands() {
        assertEquals(BoardScoreCalculator.STAGE_CLIMAX, BoardScoreCalculator.stageOf(new BigDecimal("85"), false));
        assertEquals(BoardScoreCalculator.STAGE_FERMENT, BoardScoreCalculator.stageOf(new BigDecimal("60"), false));
        assertEquals(BoardScoreCalculator.STAGE_CHAOS, BoardScoreCalculator.stageOf(new BigDecimal("40"), false));
        assertEquals(BoardScoreCalculator.STAGE_EBB, BoardScoreCalculator.stageOf(new BigDecimal("39.99"), false));
        assertEquals(BoardScoreCalculator.STAGE_FORCED_EBB, BoardScoreCalculator.stageOf(new BigDecimal("90"), true));
        assertNull(BoardScoreCalculator.stageOf(null, false));
    }
    // ---------------------------------------------------------------- eval 树（score-detail 端点用）

    /** 喂一份 5 维都能出分的 metrics，eval 树按维序返回 5 个 DimEval，D3 三个复合子各 4 层。 */
    @Test
    void evaluatePopulatesDimEvalsInTreeOrder() {
        Map<String, BigDecimal> metrics = fullMetrics();
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        assertEquals(5, r.getDimEvals().size());

        NodeEval market = r.getDimEvals().get(0);
        assertEquals("market", market.getKey());
        assertEquals(Integer.valueOf(1), market.getDimNo());
        assertEquals("score_market", market.getRecordColumn());
        assertEquals(4, market.getChildren().size());

        NodeEval board = r.getDimEvals().get(2);
        assertEquals("board", board.getKey());
        assertEquals(5, board.getChildren().size());
        NodeEval promo = subEval(board.getChildren(), "promo");
        assertEquals("LAYER_WEIGHTED_BAND", promo.getScoringKind());
        assertEquals(4, promo.getChildren().size());
        assertEquals("promo_low", promo.getChildren().get(0).getKey());
        assertEquals("jr_low", promo.getChildren().get(0).getSourceKey());
        assertEquals("promo_top", promo.getChildren().get(3).getKey());

        // 数值路径与 dimScores 严格一致：dimEvals[i].score == dimScores[dimKey]
        for (NodeEval de : r.getDimEvals()) {
            BigDecimal fromMap = r.getDimScores().get(de.getKey());
            if (de.getScore() == null) {
                assertTrue(fromMap == null, "dim " + de.getKey() + " score=null 但 dimScores 有值");
            } else {
                assertEquals(0, de.getScore().compareTo(fromMap),
                        "dim " + de.getKey() + " 树/表两套 score 不一致: " + de.getScore() + " vs " + fromMap);
            }
        }
    }

    /** 缺一个 metrics 键 → 对应叶子 raw=null / score=null，父复合子按剩余层归一化，维分仍要出。 */
    @Test
    void nodeEvalMarksUnscoredWhenMetricMissing() {
        Map<String, BigDecimal> metrics = fullMetrics();
        metrics.remove("turnover_ratio"); // 量能子读不到
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);

        NodeEval market = r.getDimEvals().get(0);
        NodeEval turnover = subEval(market.getChildren(), "turnover");
        assertNull(turnover.getRaw(), "metrics 缺 key 时 raw 必须为 null（不能兜 0）");
        assertNull(turnover.getScore(), "metrics 缺 key 时 score 必须为 null（不能兜 0）");
        // 其他三子（index_env / breadth / limit_combo）仍齐 → 维分要出，且比原分不同（分母从 1.0 变 0.75）
        assertNotNull(market.getScore(), "一子未评不该拖垮整维");

        // 复合层里挖掉一层也不该拖垮复合子：拿掉 jr_mid，promo 仍要出分
        Map<String, BigDecimal> m2 = fullMetrics();
        m2.remove("jr_mid");
        Result r2 = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), m2);
        NodeEval board2 = r2.getDimEvals().get(2);
        NodeEval promo2 = subEval(board2.getChildren(), "promo");
        NodeEval promoMid = subEval(promo2.getChildren(), "promo_mid");
        assertNull(promoMid.getScore());
        assertNotNull(promo2.getScore(), "四层缺一层，剩余 3 层归一化后仍要出分");
    }

    /** 命中档要写清楚："1.15 [?,?] → 分" 或 "1.15 >= 0.95 → 70"，供 tooltip 直接展示。 */
    @Test
    void nodeEvalRecordsBandHit() {
        Map<String, BigDecimal> metrics = fullMetrics();
        metrics.put("turnover_ratio", new BigDecimal("1.15"));
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);

        NodeEval market = r.getDimEvals().get(0);
        NodeEval turnover = subEval(market.getChildren(), "turnover");
        assertEquals(0, new BigDecimal("1.15").compareTo(turnover.getRaw()));
        assertAmount("70", turnover.getScore()); // GTE 0.95 → 70
        assertNotNull(turnover.getBandHit(), "BAND_LADDER 命中必须留下人话");
        assertTrue(turnover.getBandHit().contains("1.15"), "bandHit 应含 raw，实际=" + turnover.getBandHit());
        assertTrue(turnover.getBandHit().contains("0.95"), "bandHit 应含阈值，实际=" + turnover.getBandHit());
        assertTrue(turnover.getBandHit().contains("70"), "bandHit 应含分数，实际=" + turnover.getBandHit());
        // STRATEGY 节点不写 bandHit（它压根没阶梯）
        NodeEval idx = subEval(market.getChildren(), "index_env");
        assertNull(idx.getBandHit(), "STRATEGY 节点不该有 bandHit");
    }

    private static Map<String, BigDecimal> fullMetrics() {
        Map<String, BigDecimal> metrics = boardBase("70");
        metrics.putAll(m(
                "index1_pct", 1.5, "index2_pct", 1.5, "index3_pct", 1.5,
                "turnover_ratio", 1.3, "red_ratio", 0.7, "limit_up_count", 90, "limit_down_count", 0,
                "zt_gather_pct", 40, "height_gather_pct", 90, "amount_gather_pct", 40,
                "catalyst_hardness", 5, "persistence_days", 5,
                "first_count", 60, "first_sealed_rate", 85, "first_premium_pct", 4, "first_promo_1to2_rate", 30,
                "first_1to2_big_count", 0,
                "dragon_zong_long", 95, "dragon_zhong_jun", 95, "dragon_gen_feng", 95,
                "dragon_ka_wei", 95, "dragon_fan_bao", 95));
        return metrics;
    }

    private static NodeEval subEval(List<NodeEval> nodes, String key) {
        for (NodeEval n : nodes) {
            if (key.equals(n.getKey())) {
                return n;
            }
        }
        throw new AssertionError("no nodeEval " + key);
    }
}
