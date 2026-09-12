package com.emotion.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
        // 高度/催化剂是 STRATEGY：空间板归属砍半、无题材行默认 50 的判断在 Java
        assertEquals("height_gather", theme.getSubs().get(1).getSourceKey());
        assertEquals("STRATEGY", theme.getSubs().get(1).getScoringKind());
        assertEquals("catalyst", theme.getSubs().get(3).getSourceKey());
        assertEquals("STRATEGY", theme.getSubs().get(3).getScoringKind());
        assertEquals(1.0, weightSum(theme.getSubs()), 1e-9);

        DimNode board = dim(t, "board");
        assertEquals(5, board.getSubs().size());
        SubNode promo = byKey(board.getSubs(), "promo");
        assertEquals("LAYER_WEIGHTED_BAND", promo.getScoringKind());
        assertEquals(4, promo.getChildren().size());
        assertEquals(1.0, weightSum(promo.getChildren()), 1e-9);
        assertEquals("jr_low", promo.getChildren().get(0).getSourceKey());
        assertEquals("jr_top", promo.getChildren().get(3).getSourceKey());

        // D5（2026-09-12 融合版）：高位生态 = 阵眼个体35 + 抱团资金30 + 监管压制20 + 监管反馈15
        DimNode high = dim(t, "high");
        assertEquals(0.25, high.getWeight(), 1e-9);
        assertEquals("score_high", high.getRecordColumn());
        assertEquals(4, high.getSubs().size());
        SubNode anchorInd = byKey(high.getSubs(), "anchor_ind");
        assertEquals(0.35, anchorInd.getWeight(), 1e-9);
        assertEquals(4, anchorInd.getChildren().size());
        assertEquals("d5a_action", anchorInd.getChildren().get(0).getSourceKey());
        SubNode coalition = byKey(high.getSubs(), "coalition");
        assertEquals(0.30, coalition.getWeight(), 1e-9);
        SubNode structure = byKey(coalition.getChildren(), "c_structure");
        assertEquals(4, structure.getChildren().size());
        SubNode strength = byKey(coalition.getChildren(), "c_strength");
        assertEquals(2, strength.getChildren().size());
        assertEquals("d5_feedback", byKey(high.getSubs(), "feedback").getSourceKey());
        assertEquals("STRATEGY", byKey(high.getSubs(), "feedback").getScoringKind());
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
    void strategyIndexEnv_continuous() {
        // v2.1 连续函数 score=clamp(50 + 三指均值%×30, 0, 100)：0%→50、-1%→20、+1%→80、+1.5%→95，消除原 -1% 阈值跳变
        assertEquals(0, BoardScoreCalculator.strategyIndexEnv(m("index1_pct", 1.5, "index2_pct", 1.5, "index3_pct", 1.5))
                .compareTo(new BigDecimal("95")));
        assertEquals(0, BoardScoreCalculator.strategyIndexEnv(m("index1_pct", -1.0, "index2_pct", -1.0, "index3_pct", -1.0))
                .compareTo(new BigDecimal("20")));
        assertEquals(0, BoardScoreCalculator.strategyIndexEnv(m("index1_pct", -2.0, "index2_pct", -2.0, "index3_pct", -2.0))
                .compareTo(BigDecimal.ZERO));      // 低于 0 封底
        assertEquals(0, BoardScoreCalculator.strategyIndexEnv(m("index1_pct", 0, "index2_pct", 0, "index3_pct", 0))
                .compareTo(new BigDecimal("50"))); // 平盘=中性
        // 三指全绿但均未破 -1%（原弱跌日 35）：连续函数按均值平滑滑落，不再有档位跳变
        //   均值 (-0.34-0.61-0.22)/3=-0.39 → 50-11.7=38.3；(-1-0.5-0.2)/3=-0.5667 → 32.999
        assertEquals(0, BoardScoreCalculator.strategyIndexEnv(m("index1_pct", -0.34, "index2_pct", -0.61, "index3_pct", -0.22))
                .compareTo(new BigDecimal("38.30")));
        assertEquals(0, BoardScoreCalculator.strategyIndexEnv(m("index1_pct", -1.0, "index2_pct", -0.5, "index3_pct", -0.2))
                .compareTo(new BigDecimal("32.999")));
        assertNull(BoardScoreCalculator.strategyIndexEnv(m("index1_pct", 1.5, "index2_pct", 2.0))); // 缺一指=未评
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

    // ---------------------------------------------------------------- D2 空间板归属 / 催化剂缺省

    @Test
    void strategyHeightGather_spaceBoardOwnership() {
        // 空间板不在主线：(板数比)×50 封顶 50。元件案例 50% → 25，而不是阶梯的 70
        assertAmount("25", BoardScoreCalculator.strategyHeightGather(m("height_gather_pct", 50, "space_board_in_main", 0)));
        assertAmount("45", BoardScoreCalculator.strategyHeightGather(m("height_gather_pct", 90, "space_board_in_main", 0)));
        assertAmount("20", BoardScoreCalculator.strategyHeightGather(m("height_gather_pct", 40, "space_board_in_main", 0)));
        // 空间板在主线：照旧走阶梯（50%→70、90%→95）
        assertAmount("70", BoardScoreCalculator.strategyHeightGather(m("height_gather_pct", 50, "space_board_in_main", 1)));
        assertAmount("95", BoardScoreCalculator.strategyHeightGather(m("height_gather_pct", 90, "space_board_in_main", 1)));
        // 归属旗标缺失（非自动取数路径）：退原阶梯，不外推归属
        assertAmount("70", BoardScoreCalculator.strategyHeightGather(m("height_gather_pct", 50)));
        // 缺高度比=未评
        assertNull(BoardScoreCalculator.strategyHeightGather(m("space_board_in_main", 0)));
    }

    @Test
    void strategyCatalyst_defaults50OnlyWhenMainSectorActive() {
        assertAmount("80", BoardScoreCalculator.strategyCatalyst(m("catalyst_hardness", 4)));
        assertAmount("20", BoardScoreCalculator.strategyCatalyst(m("catalyst_hardness", 1)));
        // 日内核心存在但无题材行：保守 50（人工未评）
        assertAmount("50", BoardScoreCalculator.strategyCatalyst(m("main_sector_active", 1)));
        // 连日内核心都没有（无涨停池）：整子未评，不凭空出分
        assertNull(BoardScoreCalculator.strategyCatalyst(m("zt_gather_pct", 0)));
    }

    /**
     * 9/11 元件案例完整重演：涨停聚集 22.5%(68) + 空间板不在本板块高度 25 + 成交额<15%(35)
     * + 催化剂缺省 50 + 持续性首日 50 → 加权 45.25；萌芽封顶 50 不触发。
     * 2026-09-12 D5 融合起龙头错位不再在 D2 扣分（×0.9 已下线），45.25 保持；错位只出信号，
     * 扣分归 D5 阵眼一致性叶（100→60）。
     */
    @Test
    void themeMain_yuanjianCase_noMisalignMultiplierAfterD5Fusion() {
        Map<String, BigDecimal> metrics = m(
                "zt_gather_pct", 22.5,
                "height_gather_pct", 50, "space_board_in_main", 0,
                "amount_gather_pct", 10,
                "main_sector_active", 1,
                "persistence_days", 1,
                "mainline_stage_cap", 50,
                "dragon_misalign", 1);
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        assertAmount("45.25", r.getDimScores().get("theme_main"));
        NodeEval theme = r.getDimEvals().get(1);
        // D2 不再因错位扣分也不挂 note（封顶未触发）；错位只以信号形式可见
        assertNull(theme.getNote(), "无封顶/无乘数时 note 应为 null，实际=" + theme.getNote());
        // 错位仍以信号形式输出（不扣分但看得见）
        assertTrue(r.getSignalFlags().contains(BoardScoreCalculator.SIG_ANCHOR_MISMATCH),
                "龙头错位应输出信号，实际=" + r.getSignalFlags());
        // 高度子项 25、催化子项 50（缺省）都能在 eval 树上看到
        assertAmount("25", subEval(theme.getChildren(), "height_gather").getScore());
        assertAmount("50", subEval(theme.getChildren(), "catalyst").getScore());
    }

    /** 强 readings + 萌芽天花板：加权 95.75 被压到 50；D5 融合后无错位乘数，封顶即终值。 */
    @Test
    void themeMain_sproutCeilingBinds() {
        Map<String, BigDecimal> metrics = m(
                "zt_gather_pct", 40,
                "height_gather_pct", 90, "space_board_in_main", 1,
                "amount_gather_pct", 40,
                "catalyst_hardness", 5, "main_sector_active", 1,
                "persistence_days", 5,
                "mainline_stage_cap", 50,
                "dragon_misalign", 1);
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        assertAmount("50.00", r.getDimScores().get("theme_main"));
        NodeEval theme = r.getDimEvals().get(1);
        assertTrue(theme.getNote().contains("封顶 50"), "应挂封顶说明，实际=" + theme.getNote());
        assertFalse(theme.getNote().contains("×0.9"), "D5 融合后 D2 不应再有错位乘数，实际=" + theme.getNote());
    }

    /** 退潮天花板 30：不设错位时强 readings 也只能到 30。 */
    @Test
    void themeMain_ebbCeiling() {
        Map<String, BigDecimal> metrics = m(
                "zt_gather_pct", 40,
                "height_gather_pct", 90, "space_board_in_main", 1,
                "amount_gather_pct", 40,
                "catalyst_hardness", 5, "main_sector_active", 1,
                "persistence_days", 5,
                "mainline_stage_cap", 30);
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        assertAmount("30.00", r.getDimScores().get("theme_main"));
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
        // 时间截面 PRD 子权 .30/.25/.20/.15/.10：raw=.30*76.25+.70*95=89.375；×0.8(吹哨)=71.50
        assertAmount("71.50", r.getDimScores().get("board"));
        assertAmount("71.50", r.getTotal());
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
                "max_height", 30);
    }

    // ---------------------------------------------------------------- D3 口径修正（2026-09-12）

    /** H=4：中高位/极高层叶子标 N/A（applicable=false、score 清空），不进分母；空间未打开 promo ×0.9。 */
    @Test
    void boardCalibration_h4_marksHighLayersNotApplicableAndSpacePenalty() {
        Map<String, BigDecimal> metrics = m(
                "jr_low", 15, "jr_mid", 37.5,   // 低/中位晋级，无高两层
                "prem_low", -0.6, "prem_mid", 2.37,
                "big_low", 0, "big_mid", 1,
                "sealed_home_rate", 69, "reseal_rate", 54,
                "max_height", 4);
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        NodeEval board = r.getDimEvals().get(2);
        NodeEval promo = subEval(board.getChildren(), "promo");
        NodeEval promoTop = subEval(promo.getChildren(), "promo_top");
        assertEquals(Boolean.FALSE, promoTop.getApplicable(), "H=4 极高位晋级应标 N/A");
        assertNull(promoTop.getScore(), "N/A 叶子必须剔出分母");
        NodeEval promoMid = subEval(promo.getChildren(), "promo_mid");
        assertNull(promoMid.getAdjustment(), "无小样本基数键时不打折");
        assertNotNull(promo.getAdjustment(), "H<5 晋级结构应有空间未打开修正");
        // promo 裸合成=(.15*45+.25*65)/.40=57.5 → ×0.9=51.75
        assertAmount("51.75", promo.getScore());
    }

    /** 小样本：中位晋级昨日基数<5 → 中位晋级叶子 ×0.8 并留修正说明。 */
    @Test
    void boardCalibration_smallSample_discountsMidPromoLeaf() {
        Map<String, BigDecimal> metrics = m(
                "jr_mid", 50, "jr_mid_base", 3,
                "prem_mid", 1, "big_mid", 0,
                "sealed_home_rate", 80, "reseal_rate", 80, "max_height", 4);
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        NodeEval promoMid = subEval(subEval(r.getDimEvals().get(2).getChildren(), "promo").getChildren(), "promo_mid");
        assertAmount("64.00", promoMid.getScore()); // 50%→GTE40=80 ×0.8
        assertTrue(promoMid.getAdjustment().contains("小样本"));
    }

    /** 全局跌停外溢：跌停 21 家 → 大面结构 −35（95→60）。 */
    @Test
    void boardCalibration_limitDownSpilloverCutsBigLoss() {
        Map<String, BigDecimal> metrics = boardBase("70");
        metrics.put("limit_down_count", new BigDecimal("21"));
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        NodeEval bigloss = subEval(r.getDimEvals().get(2).getChildren(), "bigloss");
        assertAmount("60.00", bigloss.getScore()); // 95-35
        assertTrue(bigloss.getAdjustment().contains("外溢 −35"));
    }

    /** 维分闸门：大盘分<40 → 大盘背离 ×0.85。龙头错位闸门 2026-09-12 起下线（只出信号，不乘维分）。 */
    @Test
    void boardCalibration_divergenceGateStacksOnDimScore() {
        // 大盘分：三指 -1% → 20 分（<40）；其余子项缺省，大盘维仍出分
        Map<String, BigDecimal> metrics = boardBase("70");
        metrics.putAll(m("index1_pct", -1, "index2_pct", -1, "index3_pct", -1,
                "turnover_ratio", 1.0, "red_ratio", 0.118, "limit_up_count", 40, "limit_down_count", 21,
                "dragon_misalign", 1));
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        NodeEval board = r.getDimEvals().get(2);
        Map<String, BoardScoreCalculator.GateInfo> gates = new LinkedHashMap<>();
        for (BoardScoreCalculator.GateInfo g : board.getGates()) {
            gates.put(g.getKey(), g);
        }
        assertTrue(gates.get("divergence").isTriggered(), "跌停21+大盘<40 应触发大盘背离");
        assertFalse(gates.containsKey("dragon_misalign"), "龙头错位闸门应已下线，实际 gates=" + gates.keySet());
        // promo95；premium95×0.8(大盘背离)=76；bigloss=95-35=60；broken95；count95（H=30）
        //   raw=.30*95+.25*76+.20*60+.15*95+.10*95=83.25；闸门仅 ×.85(背离)=70.76
        assertAmount("70.76", r.getDimScores().get("board"));
        // 错位不再乘维分，但信号仍在
        assertTrue(r.getSignalFlags().contains(BoardScoreCalculator.SIG_ANCHOR_MISMATCH));
    }

    // ---------------------------------------------------------------- D4 纯 T 日校准 + 试错-兑现背离

    /** D4 纯 T 日五子齐 → 95；结构上 first 维 5 个一级子，其中封单质量是含 2 叶的复合子。 */
    @Test
    void firstDim_pureTDayStructureAndFullScore() {
        Map<String, BigDecimal> metrics = new TreeMap<>();
        metrics.putAll(m("first_count", 60, "first_sealed_rate", 85, "first_bomb_rate", 5,
                "first_avg_seal_amount", 4, "first_yizi_ratio", 35, "first_theme_gather_pct", 45));
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        assertAmount("95.00", r.getDimScores().get("first"));
        NodeEval first = r.getDimEvals().get(3);
        assertEquals("first", first.getKey());
        assertEquals(5, first.getChildren().size());
        NodeEval sealQuality = subEval(first.getChildren(), "first_seal_quality");
        assertEquals(2, sealQuality.getChildren().size());
    }

    /** 大盘背离（跌停≥10）时：首板数量 ×0.85、封板率 −10，维分按修正子项重算。 */
    @Test
    void firstCalibration_divergenceDiscountsCountAndSealedRate() {
        Map<String, BigDecimal> metrics = new TreeMap<>();
        metrics.putAll(m("first_count", 30, "first_sealed_rate", 73, "first_bomb_rate", 27,
                "first_avg_seal_amount", 0.8, "first_yizi_ratio", 15, "first_theme_gather_pct", 18,
                "limit_down_count", 21));
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        NodeEval first = r.getDimEvals().get(3);
        NodeEval count = subEval(first.getChildren(), "first_count");
        // 30 只→GTE25=65 ×0.85=55.25
        assertAmount("55.25", count.getScore());
        assertTrue(count.getAdjustment().contains("×0.85"));
        NodeEval sealed = subEval(first.getChildren(), "first_sealed");
        // 73%→GTE70=80 −10=70
        assertAmount("70.00", sealed.getScore());
    }

    /** 试错-兑现背离度=D4−D3：首板热、连板冷 → 严重背离标签；两维任一未评 → null。 */
    @Test
    void ecologyDivergence_firstMinusBoard() {
        Map<String, BigDecimal> hot = new TreeMap<>();
        hot.putAll(m("first_count", 60, "first_sealed_rate", 85, "first_bomb_rate", 5,
                "first_avg_seal_amount", 4, "first_yizi_ratio", 35, "first_theme_gather_pct", 45));
        Result onlyFirst = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), hot);
        assertNull(onlyFirst.getEcologyDivergence(), "连板维未评时背离度应为 null");

        Map<String, BigDecimal> both = new TreeMap<>(hot);
        // 连板维给极低分：H=2、低位晋级率 0、封板率 30、无溢价/大面读数
        both.putAll(m("max_height", 2, "jr_low", 0, "sealed_home_rate", 30, "reseal_rate", 20,
                "limit_down_count", 21));
        Result r2 = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), both);
        BigDecimal d = r2.getEcologyDivergence();
        assertNotNull(d);
        assertTrue(d.doubleValue() > 30, "首板95 vs 连板冷应严重背离，实际 " + d);
        assertTrue(r2.getEcologyDivergenceLabel().contains("严重背离"));
    }

    @Test
    void manual_isClamped0to100() {
        Map<String, BigDecimal> metrics = m("d5a_action", 150); // 越界人工分（D5 阵眼行为叶）
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        assertAmount("100", r.getDimScores().get("high")); // 唯一可评子 → 维分=100
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
                "catalyst_hardness", 5, "persistence_days", 5));
        metrics.putAll(firstFullMetrics());
        metrics.putAll(d5Metrics());
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);
        // market v2.1：指数连续(均值+1.5%→95) + 量能(基础90 × 放量上涨1.2 = 108 → 封顶100) + 广度(0.7→85) + 涨跌停(95)
        //   = 95*.35 + 100*.25 + 85*.20 + 95*.20 = 94.25
        assertAmount("94.25", r.getDimScores().get("market"));
        // theme: 95*.25 + 95*.25 + 95*.20 + 100*.15 + 95*.15 = 95.75
        assertAmount("95.75", r.getDimScores().get("theme_main"));
        assertAmount("95.00", r.getDimScores().get("board"));
        assertAmount("95.00", r.getDimScores().get("first"));
        // D5：阵眼 95；结构=30%×100(占比30)+25%×90(封单40≤50)+25%×100(空间板2)+20%×95=96.5，
        //   强度 95（溢价4→95/晋级60→95）→ 抱团=.6×96.5+.4×95=95.9；
        //   压制=100（0家/0%/0扩散）；反馈=70（均涨10%、无核按钮=监管无效亢奋档）
        //   high=.35×95+.30×95.9+.20×100+.15×70=92.52
        assertAmount("92.52", r.getDimScores().get("high"));
        // 总分（维分先四舍五入到 2 位再加权，分母=1）：
        //   94.25×.22 + 95.75×.18 + 95×.22 + 95×.13 + 92.52×.25 = 94.35
        assertAmount("94.35", r.getTotal());
        assertEquals(BoardScoreCalculator.STAGE_CLIMAX, r.getStage());
    }

    /** D5 feedback 五态 STRATEGY。 */
    @Test
    void d5FeedbackStrategy_fiveStates() {
        assertNull(BoardScoreCalculator.strategyD5Feedback(new TreeMap<String, BigDecimal>()), "无键=未评");
        assertAmount("0", BoardScoreCalculator.strategyD5Feedback(m("d5f_nuke", 1, "d5f_avg", 3)));
        assertAmount("70", BoardScoreCalculator.strategyD5Feedback(m("d5f_nuke", 0, "d5f_avg", 6)));
        assertAmount("85", BoardScoreCalculator.strategyD5Feedback(m("d5f_nuke", 0, "d5f_avg", 1)));
        assertAmount("50", BoardScoreCalculator.strategyD5Feedback(m("d5f_nuke", 0, "d5f_avg", -2)));
        assertAmount("25", BoardScoreCalculator.strategyD5Feedback(m("d5f_nuke", 0, "d5f_avg", -8)));
        assertNull(BoardScoreCalculator.strategyD5Feedback(m("d5f_nuke", 0)), "nuke=0 但均价缺=未评");
    }

    /** D5 交叉信号：旗标 → 中文标签。 */
    @Test
    void d5SignalFlags() {
        assertTrue(BoardScoreCalculator.detectSignals(m("d5_sig_death", 1)).contains(BoardScoreCalculator.SIG_DEATH));
        assertTrue(BoardScoreCalculator.detectSignals(m("d5_sig_monitor_ignored", 1))
                .contains(BoardScoreCalculator.SIG_MONITOR_IGNORED));
        assertTrue(BoardScoreCalculator.detectSignals(m("d5_sig_monitor_works", 1))
                .contains(BoardScoreCalculator.SIG_MONITOR_WORKS));
        assertTrue(BoardScoreCalculator.detectSignals(m("d5_sig_sector_press", 1))
                .contains(BoardScoreCalculator.SIG_SECTOR_PRESS));
        List<String> h = BoardScoreCalculator.detectSignals(m("d5_sig_handover", 2));
        assertTrue(h.contains(BoardScoreCalculator.SIG_ANCHOR_TAKEOVER));
        assertTrue(BoardScoreCalculator.detectSignals(m("d5_sig_handover", 3))
                .contains(BoardScoreCalculator.SIG_ANCHOR_DEAD));
        assertTrue(BoardScoreCalculator.detectSignals(m("d5_sig_handover", 1))
                .contains(BoardScoreCalculator.SIG_ANCHOR_WEAK));
    }

    /** D5 新增两条强制退潮。 */
    @Test
    void d5ForcedEbb() {
        assertTrue(BoardScoreCalculator.detectForcedEbb(m("d5_force_top_break", 1)).forced);
        assertTrue(BoardScoreCalculator.detectForcedEbb(m("d5_force_death", 1)).forced);
        assertFalse(BoardScoreCalculator.detectForcedEbb(m("d5f_nuke", 1)).forced);
    }

    /** v2.1.1 量能系数收紧：普跌日量能贡献(0.25×量能分)必须严格低于指数贡献(0.35×指数分)。
     *  以 -1% 普跌口径重演：三指均值-1% → 指数分20贡献7.0；平量基础70×0.35=24.5 贡献6.125<7.0。 */
    @Test
    void downDay_volumeContributionStrictlyBelowIndex() {
        Map<String, BigDecimal> metrics = m(
                "index1_pct", -1.0, "index2_pct", -1.0, "index3_pct", -1.0,
                "turnover_ratio", 1.0,   // 平量：基础档 70
                "red_ratio", 0.118);     // 涨跌家数比≈7.5:1：广度命中惩罚档
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);

        NodeEval market = r.getDimEvals().get(0);
        NodeEval index = subEval(market.getChildren(), "index_env");
        NodeEval turnover = subEval(market.getChildren(), "turnover");
        assertAmount("20", index.getScore());        // 50 + (-1×30)
        assertAmount("24.5", turnover.getScore());   // 基础 70 × 平量下跌 0.35
        assertTrue(0.25 * turnover.getScore().doubleValue() < 0.35 * index.getScore().doubleValue(),
                "普跌日量能贡献必须严格低于指数贡献");
        assertAmount("5", subEval(market.getChildren(), "breadth").getScore()); // 0.118<0.13 → 5
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

    /**
     * v2.1：量能/指数是 STRATEGY（价量配合 + 连续函数），命中详情改由 note 携带人话串；
     * 广度仍是 BAND_LADDER，bandHit 保留（"0.7 ≥ 0.6 → 85"）。
     */
    @Test
    void nodeEvalRecordsNoteForStrategyAndBandHitForLadder() {
        Map<String, BigDecimal> metrics = fullMetrics();
        metrics.put("turnover_ratio", new BigDecimal("1.15"));
        Result r = BoardScoreCalculator.evaluate(BoardScoreCalculator.builtinTree(), metrics);

        NodeEval market = r.getDimEvals().get(0);

        // 量能：基础 GTE0.95→70 × 放量上涨1.2(fullMetrics 三指均值+1.5%) = 84
        NodeEval turnover = subEval(market.getChildren(), "turnover");
        assertAmount("84", turnover.getScore());
        assertNull(turnover.getBandHit(), "STRATEGY 节点不该有 bandHit");
        assertNotNull(turnover.getNote(), "STRATEGY 节点应带价量配合 note");
        assertTrue(turnover.getNote().contains("1.2"), "note 应含价量系数，实际=" + turnover.getNote());

        // 广度仍是阶梯：命中档留人话
        NodeEval breadth = subEval(market.getChildren(), "breadth");
        assertEquals(0, new BigDecimal("0.7").compareTo(breadth.getRaw()));
        assertNotNull(breadth.getBandHit(), "BAND_LADDER 命中必须留下人话");
        assertTrue(breadth.getBandHit().contains("0.6"), "bandHit 应含阈值，实际=" + breadth.getBandHit());

        // 指数 STRATEGY：连续函数 note，无 bandHit
        NodeEval idx = subEval(market.getChildren(), "index_env");
        assertNull(idx.getBandHit(), "STRATEGY 节点不该有 bandHit");
        assertNotNull(idx.getNote(), "指数连续函数应带 note");
    }

    /**
     * D5 高位生态全套读数：阵眼四子项 95；结构=96.5/强度=95（抱团 95.9）；压制 100；反馈 70（均涨 10%）。
     * 各原始值对应的档位见 {@link #allDimsScored_totalIsDirectWeightedSum} 里的算式注释。
     */
    private static Map<String, BigDecimal> d5Metrics() {
        return m(
                "d5a_action", 95, "d5a_height", 95, "d5a_seal", 95, "d5a_consist", 95,
                "d5c_ratio", 30, "d5c_seal", 40, "d5c_top", 2, "d5c_tier", 95,
                "d5c_prem", 4, "d5c_jr", 60,
                "d5p_count", 0, "d5p_high_ratio", 0, "d5p_spread", 0,
                "d5f_nuke", 0, "d5f_avg", 10);
    }

    private static Map<String, BigDecimal> fullMetrics() {
        Map<String, BigDecimal> metrics = boardBase("70");
        metrics.putAll(m(
                "index1_pct", 1.5, "index2_pct", 1.5, "index3_pct", 1.5,
                "turnover_ratio", 1.3, "red_ratio", 0.7, "limit_up_count", 90, "limit_down_count", 0,
                "zt_gather_pct", 40, "height_gather_pct", 90, "amount_gather_pct", 40,
                "catalyst_hardness", 5, "persistence_days", 5));
        metrics.putAll(firstFullMetrics());
        metrics.putAll(d5Metrics());
        return metrics;
    }

    /** D4 首板生态纯 T 日五子全部喂满 95 档（封单质量复合子两叶齐 95）。 */
    private static Map<String, BigDecimal> firstFullMetrics() {
        return m(
                "first_count", 60,
                "first_sealed_rate", 85,
                "first_bomb_rate", 5,
                "first_avg_seal_amount", 4,
                "first_yizi_ratio", 35,
                "first_theme_gather_pct", 45);
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
