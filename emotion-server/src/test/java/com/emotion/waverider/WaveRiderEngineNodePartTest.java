package com.emotion.waverider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.emotion.entity.NodeEvent;

/**
 * 评分里那一项节点分（PRD §6.2 的 {@code W_node × node_type_weight}）。
 *
 * <p>这里只钉判据本身，不跑引擎：引擎要连一堆 mapper，而这一项最容易写错的恰恰是
 * 「什么时候给分、给多少」这三档。
 */
class WaveRiderEngineNodePartTest {

    /** 未命中任何节点 → 0 分。这一档错了，全场候选都会平白多 0.2。 */
    @Test
    void notANodeStockScoresZero() {
        assertEquals(0.0, WaveRiderEngine.nodePart(null, new WaveRiderConfig().getNodeTypeWeights()), 1e-9);
    }

    /**
     * 命中节点但没录类型 → 按 1.0 计。
     *
     * <p>现存 6 条 {@code t_node_event} 的 node_type 全是 NULL（自动节点识别没实现，
     * 节点追踪表单也不给选类型）。这一档要是跟着字面公式判 0，节点分就等于没接。
     */
    @Test
    void untypedNodeStillScoresFull() {
        NodeEvent e = new NodeEvent();
        assertEquals(1.0, WaveRiderEngine.nodePart(e, new WaveRiderConfig().getNodeTypeWeights()), 1e-9);
    }

    /** 有类型就按类型分级：启动 1.0／切换 0.8／分歧 0.6（权重表用配置的默认值，不在此另抄一份）。 */
    @Test
    void typedNodeUsesTheConfiguredWeight() {
        Map<String, Double> weights = new WaveRiderConfig().getNodeTypeWeights();
        assertEquals(1.0, WaveRiderEngine.nodePart(typed("START"), weights), 1e-9);
        assertEquals(0.8, WaveRiderEngine.nodePart(typed("SWITCH"), weights), 1e-9);
        assertEquals(0.6, WaveRiderEngine.nodePart(typed("DIVERGE"), weights), 1e-9);
    }

    /**
     * 有类型、但权重表里查不到这个类型 → 0 分，不替它猜一个。
     *
     * <p>拼错的、以后新加却忘了配权重的类型都落在这一档：宁可这项不给分，
     * 也不要出现「界面没说给了、分数里却混进一个凭空的权重」。
     * 破壁一族现在在表里了（见 {@link #spaceBreakTypesCarryTheirWeights}），
     * 拿一个没配过的键才判得了这条——V34 那套「破局形态」的键就一直是这种状态。
     */
    @Test
    void typeMissingFromWeightTableScoresZero() {
        assertEquals(0.0, WaveRiderEngine.nodePart(typed("BREAK_FORM"),
                new WaveRiderConfig().getNodeTypeWeights()), 1e-9);
        assertEquals(0.0, WaveRiderEngine.nodePart(typed("START"),
                new LinkedHashMap<String, Double>()), 1e-9);
    }

    /**
     * 空间轴两个类型自带权重：试探 0.6／成功 1.0。
     *
     * <p>0.6 不是保守系数，是"未确认"本身的价：试探只说明有票追平了那面壁，
     * 成不成要等它次日续板；续板成功就是新周期在位龙，按满分给。
     * 与高低切一族同理，这两个键必须和判定同一轮上线——{@code nodePart} 对
     * 「有类型但表里查不到」是给 0 分的，先立节点后配权重等于白立。
     */
    @Test
    void spaceBreakTypesCarryTheirWeights() {
        Map<String, Double> weights = new WaveRiderConfig().getNodeTypeWeights();
        assertEquals(0.6, WaveRiderEngine.nodePart(typed("SPACE_BREAK"), weights), 1e-9);
        assertEquals(1.0, WaveRiderEngine.nodePart(typed("SPACE_BREAK_NEXT"), weights), 1e-9);
    }

    /**
     * 高低切一族分流出来就自带权重：转切 0.35／补位 0.3／接位 0.2。
     *
     * <p>这一档必须和判定同一轮上线：{@code nodePart} 对「有类型但表里查不到」是给 0 分的，
     * 复算第一次把节点标成补位时若权重表还不认，那条节点的节点分会从 1.0 无声掉到 0。
     */
    @Test
    void highLowSplitTypesCarryTheirOwnWeights() {
        Map<String, Double> weights = new WaveRiderConfig().getNodeTypeWeights();
        assertEquals(0.35, WaveRiderEngine.nodePart(typed("SWITCH_CROSS"), weights), 1e-9);
        assertEquals(0.3, WaveRiderEngine.nodePart(typed("FILL_SAME"), weights), 1e-9);
        assertEquals(0.2, WaveRiderEngine.nodePart(typed("SPLIT_PENDING"), weights), 1e-9);
    }

    /**
     * 这三个类型之前落库的版本快照里没有对应的键，读进来要按默认值补齐；<b>已有键一个都不改</b>。
     *
     * <p>补齐只发生在 parse 之后、不回写快照：历史版本只读，{@code t_strategy_run.version_id}
     * 要靠它原样复算。不补则老策略遇到被复算标成补位的节点直接拿 0 分。
     */
    @Test
    void storedConfigWithoutTheNewKeysGetsThemBackfilled() {
        WaveRiderConfig cfg = new WaveRiderConfig();
        Map<String, Double> legacy = new LinkedHashMap<>();
        legacy.put("START", 1.2);
        legacy.put("SWITCH", 0.8);
        legacy.put("DIVERGE", 0.6);
        cfg.setNodeTypeWeights(legacy);

        cfg.fillMissingNodeTypeWeights();

        Map<String, Double> after = cfg.getNodeTypeWeights();
        assertEquals(1.2, after.get("START"), 1e-9);
        assertEquals(0.35, after.get("SWITCH_CROSS"), 1e-9);
        assertEquals(0.3, after.get("FILL_SAME"), 1e-9);
        assertEquals(0.2, after.get("SPLIT_PENDING"), 1e-9);
    }

    private static NodeEvent typed(String nodeType) {
        NodeEvent e = new NodeEvent();
        e.setNodeType(nodeType);
        return e;
    }
}
