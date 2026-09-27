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
     * <p>{@code SPACE_BREAK} 这类还没进权重表的节点就是这一档：宁可这项不给分，
     * 也不要出现「界面没说给了、分数里却混进一个凭空的权重」。
     */
    @Test
    void typeMissingFromWeightTableScoresZero() {
        assertEquals(0.0, WaveRiderEngine.nodePart(typed("SPACE_BREAK"),
                new WaveRiderConfig().getNodeTypeWeights()), 1e-9);
        assertEquals(0.0, WaveRiderEngine.nodePart(typed("START"),
                new LinkedHashMap<String, Double>()), 1e-9);
    }

    private static NodeEvent typed(String nodeType) {
        NodeEvent e = new NodeEvent();
        e.setNodeType(nodeType);
        return e;
    }
}
