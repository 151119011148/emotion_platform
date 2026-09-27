package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.entity.MarketStock;
import com.emotion.entity.NodeEvent;
import com.emotion.vo.NodeSuggestVO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 高低切分流（{@link NodeSuggestService#resolveSplit}）：补位与转切<b>只差题材同不同属性</b>，
 * 判据其余部分完全相同，所以这里钉的就是那一个岔口，和分流没到点时该不该停在接位。
 *
 * <p>两个真实案例按他给的口径做断言：
 * 8/21 金健（农业）断板→深中华（黄金）是<b>转切</b>，借的是老龙死掉腾出来的势；
 * 9/4 国芳（百货）断板→百大（百货）是<b>补位</b>，借的是老龙的题材余温。
 * 把这两只判反，整套分类就是倒的，所以两条各钉一个方向。
 *
 * <p>全内存 fixture，不碰 DB / Spring——decide 那半边本来就是纯函数。
 */
class NodeSuggestServiceSplitTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 4);

    /** 同属性 + 窗口内 ≥2 只助攻 → 补位。9/4 国芳断板、百大接位、东百与中百首板跟进。 */
    @Test
    void sameThemeWithEnoughHelpersIsFill() {
        NodeSuggestService.Readings r = readings("百货");
        r.supportRows = Arrays.asList(
                zt("000715", "东百集团", "百货"),
                zt("000759", "中百集团", "百货"));

        NodeSuggestService.TypeSplit split = NodeSuggestService.resolveSplit(r, taker("600738", "百大集团", "百货"));

        assertEquals(NodeSuggestService.TYPE_FILL_SAME, split.type);
        assertTrue(split.sameSeat);
        assertEquals(2, split.support);
    }

    /** 异属性 + 窗口内 ≥2 只助攻 → 转切。8/21 金健断板、深中华接位、黄金板块两只跟涨。 */
    @Test
    void crossThemeWithEnoughHelpersIsSwitch() {
        NodeSuggestService.Readings r = readings("农业");
        r.supportRows = Arrays.asList(
                zt("600988", "赤峰黄金", "黄金"),
                zt("600547", "山东黄金", "黄金"));

        NodeSuggestService.TypeSplit split =
                NodeSuggestService.resolveSplit(r, taker("000011", "深中华Ａ", "黄金"));

        assertEquals(NodeSuggestService.TYPE_SWITCH_CROSS, split.type);
        assertEquals(false, split.sameSeat);
    }

    /** 异属性但只有一只跟：方向还没立住，还在窗口里就等下去。 */
    @Test
    void oneHelperInsideWindowStaysPending() {
        NodeSuggestService.Readings r = readings("农业");
        r.splitWindowDays = 6;
        r.splitWindowLanded = 2;
        r.supportRows = Arrays.asList(zt("600988", "赤峰黄金", "黄金"));

        NodeSuggestService.TypeSplit split =
                NodeSuggestService.resolveSplit(r, taker("000011", "深中华Ａ", "黄金"));

        assertEquals(NodeSuggestService.TYPE_SPLIT_PENDING, split.type);
        assertTrue(split.note.contains("还差 1 只"), split.note);
    }

    /**
     * 窗口已走完仍然凑不齐助攻：按状态机该降孤板，但作废判定本轮没实现，所以留在接位并把话说清楚。
     *
     * <p>这条钉的是"不许静默"——窗口到期和窗口没到期是两种完全不同的空，note 必须分得出来。
     */
    @Test
    void expiredWindowWithoutHelpersSaysSoInsteadOfVanishing() {
        NodeSuggestService.Readings r = readings("百货");
        r.splitWindowDays = 4;
        r.splitWindowLanded = 4;
        r.supportRows = new ArrayList<>();

        NodeSuggestService.TypeSplit split = NodeSuggestService.resolveSplit(r, taker("600738", "百大集团", "百货"));

        assertEquals(NodeSuggestService.TYPE_SPLIT_PENDING, split.type);
        assertTrue(split.note.contains("已走完"), split.note);
        assertTrue(split.note.contains("作废判定本轮未实现"), split.note);
    }

    /** 老龙自己的涨停不算它给自己腾的势的助攻。 */
    @Test
    void anchorItselfIsNotAHelper() {
        NodeSuggestService.Readings r = readings("农业");
        r.anchorCode = "000551";
        r.supportRows = Arrays.asList(
                zt("000551", "创力集团", "黄金"),
                zt("600988", "赤峰黄金", "黄金"));

        NodeSuggestService.TypeSplit split =
                NodeSuggestService.resolveSplit(r, taker("000011", "深中华Ａ", "黄金"));

        assertEquals(1, split.support);
        assertEquals(NodeSuggestService.TYPE_SPLIT_PENDING, split.type);
    }

    /** 接位票自己不数进助攻，否则一只票自己连板就能把方向"立住"。 */
    @Test
    void takerItselfIsNotAHelper() {
        NodeSuggestService.Readings r = readings("农业");
        r.supportRows = Arrays.asList(
                zt("000011", "深中华Ａ", "黄金"),
                zt("000011", "深中华Ａ", "黄金"),
                zt("600988", "赤峰黄金", "黄金"));

        NodeSuggestService.TypeSplit split =
                NodeSuggestService.resolveSplit(r, taker("000011", "深中华Ａ", "黄金"));

        assertEquals(1, split.support);
    }

    /** 老龙的行业取不到就不猜：保持未识别，note 说清是哪一侧缺。 */
    @Test
    void missingAnchorThemeLeavesTypeUnrecognized() {
        NodeSuggestService.Readings r = readings(null);
        r.supportRows = Arrays.asList(
                zt("600988", "赤峰黄金", "黄金"),
                zt("600547", "山东黄金", "黄金"));

        NodeSuggestService.TypeSplit split =
                NodeSuggestService.resolveSplit(r, taker("000011", "深中华Ａ", "黄金"));

        assertNull(split.type);
        assertTrue(split.note.contains("判不了"), split.note);
    }

    /** 板高没登记 ⇒ 窗口长度定不了 ⇒ 分不了流，但这不该卡住状态的采纳，所以只是 note。 */
    @Test
    void noWindowLengthLeavesTypeUnrecognized() {
        NodeSuggestService.Readings r = readings("百货");
        r.splitWindowDays = null;
        r.supportRows = Arrays.asList(
                zt("000715", "东百集团", "百货"),
                zt("000759", "中百集团", "百货"));

        NodeSuggestService.TypeSplit split = NodeSuggestService.resolveSplit(r, taker("600738", "百大集团", "百货"));

        assertNull(split.type);
        assertTrue(split.note.contains("板高"), split.note);
    }

    // ---------- decide 那一侧的接线 ----------

    /**
     * 类型走的是既有那条采纳闸门：建议值与中文标签都要挂到 VO 上，并且进指纹。
     *
     * <p>进指纹不是细节而是纪律——没在界面上露过的值不该被"采纳"这个动作顺手写进库。
     */
    @Test
    void decideCarriesTypeIntoVoAndFingerprint() {
        NodeSuggestService.Readings r = readings("百货");
        r.limitUpCount = 88;
        r.limitDownCount = 3;
        r.t1 = D0.plusDays(1);
        r.anchorMatched = true;
        r.anchorCode = "600xxx";
        r.anchorName = "国芳集团";
        r.candidates = Arrays.asList(zt2("600738", "百大集团", "百货", 2));
        r.t1Boards = new java.util.TreeMap<>();
        r.t1Boards.put("600738", 3);
        r.maxBoards.put("600738", 3);
        r.supportRows = Arrays.asList(
                zt("000715", "东百集团", "百货"),
                zt("000759", "中百集团", "百货"));

        NodeSuggestVO vo = NodeSuggestService.decide(node(), r, new ObjectMapper());

        assertEquals(NodeSuggestService.TYPE_FILL_SAME, vo.getNodeType());
        assertEquals("补位节点", vo.getNodeTypeLabel());
        assertTrue(vo.getFingerprint().contains("\"nodeType\":\"补位节点\""), vo.getFingerprint());
        assertTrue(vo.getReason().contains("补位节点"), vo.getReason());
    }

    /** 判不出类型时不硬塞一个词，而是把"为什么判不了"出声到 warnings：一片未识别里得能分出原因。 */
    @Test
    void unresolvedTypeSpeaksUpInWarnings() {
        NodeSuggestService.Readings r = readings("百货");
        r.splitWindowDays = null;

        NodeSuggestVO vo = NodeSuggestService.decide(node(), r, new ObjectMapper());

        assertNull(vo.getNodeType());
        assertNull(vo.getNodeTypeLabel());
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.startsWith("节点类型未识别｜")),
                String.valueOf(vo.getWarnings()));
    }

    /** 类型 → 中文名的闭集由后端一处定；新加的词漏了 case 会退成 null（前端显示未识别）。 */
    @Test
    void newTypesHaveDisplayNames() {
        assertEquals("接位 · 待定", NodeService.nodeTypeLabel(NodeSuggestService.TYPE_SPLIT_PENDING));
        assertEquals("补位节点", NodeService.nodeTypeLabel(NodeSuggestService.TYPE_FILL_SAME));
        assertEquals("转切节点", NodeService.nodeTypeLabel(NodeSuggestService.TYPE_SWITCH_CROSS));
        // 反义定义不许当兜底：认不出来就是没有名字
        assertNull(NodeService.nodeTypeLabel("WHATEVER"));
    }

    // ---------- fixture ----------

    /** 一条按高低切判据取齐了原料的复算输入；anchorTheme 传 null 模拟老龙行业缺失。 */
    private static NodeSuggestService.Readings readings(String anchorTheme) {
        NodeSuggestService.Readings r = new NodeSuggestService.Readings();
        r.d0 = D0;
        r.sector = anchorTheme;
        r.splitWindowDays = 4;
        r.splitWindowLanded = 4;
        return r;
    }

    private static NodeEvent node() {
        NodeEvent n = new NodeEvent();
        n.setD0Date(D0);
        n.setSystemType("A");
        n.setAnchorMaxBoard(5);
        return n;
    }

    private static NodeSuggestVO.Candidate taker(String code, String name, String industry) {
        NodeSuggestVO.Candidate c = new NodeSuggestVO.Candidate();
        c.setCode(code);
        c.setName(name);
        c.setIndustry(industry);
        c.setMaxBoard(3);
        return c;
    }

    private static MarketStock zt(String code, String name, String industry) {
        MarketStock m = new MarketStock();
        m.setCode(code);
        m.setName(name);
        m.setIndustry(industry);
        m.setPool(MarketStock.POOL_LIMIT_UP);
        m.setTradeDate(D0.plusDays(1));
        return m;
    }

    private static MarketStock zt2(String code, String name, String industry, int consecutive) {
        MarketStock m = zt(code, name, industry);
        m.setTradeDate(D0);
        m.setConsecutive(consecutive);
        return m;
    }
}
