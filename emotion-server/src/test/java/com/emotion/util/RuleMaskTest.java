package com.emotion.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.emotion.util.BoardScoreCalculator.GateInfo;
import com.emotion.util.BoardScoreCalculator.NodeEval;

/**
 * 抹规则抹错方向有两种，都得钉住：抹少了＝规则照旧泄漏，抹多了＝分数跟着消失（比泄漏更显眼）。
 * 递归那一层是现实里最容易漏的——只抹维节点、忘了 children，页面上的档位阈值一行都不少。
 */
class RuleMaskTest {

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                2L, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private NodeEval node(String key, String label, String weight) {
        NodeEval n = new NodeEval();
        n.setKey(key);
        n.setLabel(label);
        n.setWeight(new BigDecimal(weight));
        n.setScoringKind(BoardScoreCalculator.BAND_LADDER);
        n.setSourceKey(key + "_src");
        n.setRaw(new BigDecimal("23.50"));
        n.setScore(new BigDecimal("60.00"));
        n.setBandHit("23.5 [20,30] → 60");
        n.setNote("中位晋级率 20~30% 给 60 分");
        n.setAdjustment("×0.8 小样本");
        return n;
    }

    /** 维 → 复合子 → 叶，三层；每层都带规则字段。 */
    private List<NodeEval> threeLevelTree() {
        NodeEval dim = node("board", "连板生态", "0.22");
        dim.setDimNo(3);
        dim.setRecordColumn("board_score");
        dim.setScoringKind(BoardScoreCalculator.WEIGHTED_SUM);
        dim.setGates(new ArrayList<>(Collections.singletonList(new GateInfo(
                "whistle", "中位吹哨", new BigDecimal("0.85"), true, "中位晋级率 8% < 10%"))));

        NodeEval sub = node("promo", "晋级结构", "0.40");
        sub.setScoringKind(BoardScoreCalculator.LAYER_WEIGHTED_BAND);

        NodeEval leaf = node("jr_mid", "中位晋级率", "0.50");
        sub.setChildren(new ArrayList<>(Collections.singletonList(leaf)));
        dim.setChildren(new ArrayList<>(Collections.singletonList(sub)));
        return new ArrayList<>(Collections.singletonList(dim));
    }

    @Test
    void 普通用户抹掉权重命中档修正与闸门但保留分数与读数() {
        loginAs("USER");
        List<NodeEval> dims = threeLevelTree();

        RuleMask.stripEvalTree(dims);

        NodeEval dim = dims.get(0);
        // 结果留着
        assertEquals("board", dim.getKey());
        assertEquals("连板生态", dim.getLabel());
        assertEquals(3, dim.getDimNo());
        assertEquals(new BigDecimal("60.00"), dim.getScore());
        assertEquals(new BigDecimal("23.50"), dim.getRaw());
        // 规则没了
        assertNull(dim.getWeight());
        assertNull(dim.getBandHit());
        assertNull(dim.getAdjustment());
        assertNull(dim.getNote());
        assertNull(dim.getScoringKind());
        assertNull(dim.getSourceKey());
        assertNull(dim.getRecordColumn());
        // 闸门置空数组而不是 null：前端按 gates?.length 判渲染，null 与"没有闸门"要能区分开
        assertNotNull(dim.getGates());
        assertTrue(dim.getGates().isEmpty());
    }

    @Test
    void 递归抹到第三层叶子() {
        loginAs("USER");
        List<NodeEval> dims = threeLevelTree();

        RuleMask.stripEvalTree(dims);

        NodeEval leaf = dims.get(0).getChildren().get(0).getChildren().get(0);
        assertEquals("jr_mid", leaf.getKey());
        assertEquals(new BigDecimal("60.00"), leaf.getScore());
        assertNull(leaf.getWeight(), "叶子的权重也要抹，否则四项权重之和等于把满分公式交出去");
        assertNull(leaf.getBandHit(), "bandHit 一个串就给出算符＋两个阈值＋档分");
        assertNull(leaf.getNote());
        assertNull(leaf.getAdjustment());
        assertNull(leaf.getSourceKey());
        // 中间那层也不能漏
        NodeEval sub = dims.get(0).getChildren().get(0);
        assertNull(sub.getWeight());
        assertNull(sub.getScoringKind());
    }

    @Test
    void 超管原样返回() {
        loginAs("SUPER_ADMIN");
        List<NodeEval> dims = threeLevelTree();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("funnel", Collections.singletonList("连板≥2"));
        body.put("currentConfig", new Object());
        String[] notes = {"打分模型 five_dim_v2"};

        RuleMask.stripEvalTree(dims);
        RuleMask.omitKeys(body, "funnel", "currentConfig");
        String[] kept = RuleMask.unlessSuperAdmin(notes, n -> n[0] = null);

        NodeEval leaf = dims.get(0).getChildren().get(0).getChildren().get(0);
        assertEquals(new BigDecimal("0.50"), leaf.getWeight());
        assertEquals("23.5 [20,30] → 60", leaf.getBandHit());
        assertEquals(1, dims.get(0).getGates().size());
        assertTrue(body.containsKey("funnel"));
        assertTrue(body.containsKey("currentConfig"));
        assertEquals("打分模型 five_dim_v2", kept[0]);
    }

    @Test
    void 未登录视同普通用户() {
        // Quartz 走的就是这条路：TaskDispatchJob 直接 task.run()，线程上没有 SecurityContext。
        // 这里若是 false 而不是 true，抹规则就会静默失效在定时任务里。
        SecurityContextHolder.clearContext();
        assertFalse(RuleMask.canSeeRules());

        List<NodeEval> dims = threeLevelTree();
        RuleMask.stripEvalTree(dims);
        assertNull(dims.get(0).getWeight());
    }

    @Test
    void 抹规则不碰共享配置树() {
        loginAs("USER");
        // ScoringModelStore.cachedTree 是跨请求共享的；这里用 builtinTree() 顶替它，
        // 断言抹 eval 树不会顺着引用改回配置树——真改到了就是所有人的打分一起被抹平。
        ScoringTree shared = BoardScoreCalculator.builtinTree();
        BoardScoreCalculator.Result result = BoardScoreCalculator.evaluate(shared, new LinkedHashMap<>());
        assertFalse(result.getDimEvals().isEmpty(), "引擎应当产出 5 维 eval 树");

        // 快照而不是断言 >0：不依赖 builtinTree 里恰好没有 0 权重的子指标
        Map<String, Double> weightsBefore = new LinkedHashMap<>();
        for (DimNode dim : shared.getDims()) {
            weightsBefore.put(dim.getDimKey(), dim.getWeight());
            for (SubNode sub : dim.getSubs()) {
                weightsBefore.put(dim.getDimKey() + "/" + sub.getSubKey(), sub.getWeight());
            }
        }
        assertFalse(weightsBefore.isEmpty());

        RuleMask.stripEvalTree(result.getDimEvals());

        for (DimNode dim : shared.getDims()) {
            assertEquals(weightsBefore.get(dim.getDimKey()), dim.getWeight(), 1e-9,
                    "配置树的维权重被抹了：" + dim.getDimKey());
            for (SubNode sub : dim.getSubs()) {
                assertEquals(weightsBefore.get(dim.getDimKey() + "/" + sub.getSubKey()), sub.getWeight(), 1e-9,
                        "配置树的子权重被抹了：" + sub.getSubKey());
            }
        }
        // eval 树确实抹到了（否则上面那几条断言是空转）
        assertNull(result.getDimEvals().get(0).getWeight());
    }

    @Test
    void 普通用户拿不到配置与漏斗键而其余键与顺序不变() {
        loginAs("USER");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tradeDate", "2026-10-09");
        body.put("candidates", Arrays.asList("华瓷股份", "新赛股份"));
        body.put("funnel", Collections.singletonList("连板≥2 → 12 只"));
        body.put("currentConfig", Collections.singletonMap("min_board_count", 2));
        body.put("reviewPool", null);

        Map<String, Object> out = RuleMask.omitKeys(body, "funnel", "currentConfig");

        assertSame(body, out, "就地删键，返回同一个 Map");
        assertFalse(out.containsKey("funnel"), "漏斗键名里嵌着活的 min_board_count");
        assertFalse(out.containsKey("currentConfig"));
        assertEquals(Arrays.asList("tradeDate", "candidates", "reviewPool"), new ArrayList<>(out.keySet()));
        // reviewPool 的 null 是"验证日还没到"的信号，不能被当成规则删掉
        assertTrue(out.containsKey("reviewPool"));
        assertNull(out.get("reviewPool"));
    }

    @Test
    void 抹字段只作用于传进来的那一个对象() {
        loginAs("USER");
        String[] a = {"仓位系数 0.80"};
        String[] b = {"仓位系数 0.80"};

        RuleMask.unlessSuperAdmin(a, x -> x[0] = null);

        assertNull(a[0]);
        assertEquals("仓位系数 0.80", b[0]);
        // null 入参不该炸：调用方是六个 controller，谁传进来什么都可能
        assertNull(RuleMask.unlessSuperAdmin(null, x -> {
            throw new IllegalStateException("不该被调用");
        }));
        assertNull(RuleMask.omitKeys(null, "funnel"));
        RuleMask.stripEvalTree(null);
    }
}
