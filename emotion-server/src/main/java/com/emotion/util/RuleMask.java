package com.emotion.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.emotion.util.BoardScoreCalculator.NodeEval;

/**
 * 按角色裁掉响应里的「计算规则」：权重、档位阈值、闸门系数、判据原文、修正说明。
 * 分数、温度、原始读数、清单行一律保留——那是结果，不是规则。
 *
 * <p>判据只有一个（{@link AuthContext#isSuperAdmin()}），和前端 {@code utils/ruleVisibility.js} 对齐。
 * 两层都要做：后端抹掉的字段，前端往往有 bundle 内常量兜底（{@code IndicatorCards.vue} 的
 * {@code evalNode?.weight ?? meta.weight}），只抹后端等于把打包进去的常量印出来。
 *
 * <p><b>入参必须是本次请求新建的对象。</b>就地改字段，改到了共享对象就是污染所有人：
 * {@code ScoringModelStore.cachedTree} 是 {@code volatile} 的跨请求缓存，抹它会改掉所有人的打分。
 * 已核实可就地抹的：{@code NodeEval} 树（{@code BoardScoreCalculator.evaluate} 每次新建）、
 * {@code ScoringModelVO}（{@code ScoringModelStore.effective()} 每次新建）、
 * {@code WaveRiderConfig}（{@code configOfVersion} 每次重解析 JSON）。
 *
 * <p>Map 删键、POJO 置 null：{@code funnel} / {@code currentConfig} 这些 Map 里 null 本身是有意义的
 * 信号（见 {@code WaveRiderController}），删键才不歧义；POJO 多几个 {@code "weight": null} 不泄露任何东西。
 *
 * <p>定时任务线程没有 SecurityContext（{@code TaskDispatchJob} 直接 {@code task.run()}），
 * 所以那里 {@link #canSeeRules()} 恒为 false——闸门只能加在 controller 边界，
 * 绝不能加进 {@code WaveRiderConfigService.currentConfig} 这类被 Quartz 也走的服务里。
 */
public final class RuleMask {

    private RuleMask() {
    }

    /** 当前请求能不能看到计算规则。 */
    public static boolean canSeeRules() {
        return AuthContext.isSuperAdmin();
    }

    /**
     * 超管原样返回；否则执行 {@code strip} 后返回。用于逐字段抹一个 VO。
     */
    public static <T> T unlessSuperAdmin(T value, Consumer<T> strip) {
        if (value != null && !canSeeRules()) {
            strip.accept(value);
        }
        return value;
    }

    /**
     * 超管原样返回；否则就地删掉这些键。键不存在不报错。
     */
    public static Map<String, Object> omitKeys(Map<String, Object> map, String... keys) {
        if (map == null || canSeeRules()) {
            return map;
        }
        for (String key : keys) {
            map.remove(key);
        }
        return map;
    }

    /**
     * 递归抹掉整棵 eval 树的规则字段（含 {@code children}，漏了递归是这类改动最常见的 bug）。
     *
     * <p>留：{@code key}/{@code label}/{@code dimNo}/{@code raw}/{@code score}/{@code applicable}/{@code children}。
     * 抹：{@code weight}（权重）、{@code bandHit}（形如 {@code "1.15 [1,1.3] → 60"}，一个串同时给出算符、
     * 两个阈值和档分）、{@code adjustment}（修正系数）、{@code note}（判据人话）、{@code scoringKind}/
     * {@code sourceKey}/{@code recordColumn}（算法与取数口）、{@code gates}（闸门条件与系数，置空数组
     * 而不是 null，前端按 {@code gates?.length} 判渲染）。
     */
    public static void stripEvalTree(List<NodeEval> dims) {
        if (dims == null || canSeeRules()) {
            return;
        }
        for (NodeEval node : dims) {
            stripNode(node);
        }
    }

    private static void stripNode(NodeEval node) {
        if (node == null) {
            return;
        }
        node.setWeight(null);
        node.setBandHit(null);
        node.setAdjustment(null);
        node.setNote(null);
        node.setScoringKind(null);
        node.setSourceKey(null);
        node.setRecordColumn(null);
        node.setGates(new ArrayList<>());
        List<NodeEval> children = node.getChildren();
        if (children != null) {
            for (NodeEval child : children) {
                stripNode(child);
            }
        }
    }
}
