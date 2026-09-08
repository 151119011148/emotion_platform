package com.emotion.service;

import static com.emotion.service.NodeSuggestService.STATUS_INVALID;
import static com.emotion.service.NodeSuggestService.STATUS_PENDING;
import static com.emotion.service.NodeSuggestService.STATUS_VALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.emotion.entity.MarketStock;
import com.emotion.entity.NodeEvent;
import com.emotion.service.NodeSuggestService.Readings;
import com.emotion.vo.NodeSuggestVO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 节点状态复算的判定半边。
 *
 * <p>用 2026-08-31 深中华A 那一组做已知答案：他自己文档 §七 里写死了"D0 十只二板、5 只晋级三板
 * （50%）、老龙未反包、节点有效"，库里 {@code t_market_stock} 那两天的明细算出来正是这组数。
 * 判据跑偏时最先错的就是这条——它是这套东西唯一对得上历史答案的样本。
 *
 * <p>另一半比判据更要紧：缺数时必须说"缺数"。全文最反复出现的断言是
 * {@code t1PromotionCount == null} 而不是 0，因为"0 只晋级"在他文档里等于"节点失败"。
 */
class NodeSuggestServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate D0 = LocalDate.of(2026, 8, 31);
    private static final LocalDate T1 = LocalDate.of(2026, 9, 1);

    /** 文档 §七 那一行：系统A、锚定龙头深中华A 7板、D0＝2026-08-31。 */
    private static NodeEvent shenZhonghuaA() {
        NodeEvent node = new NodeEvent();
        node.setId(3L);
        node.setUserId(2L);
        node.setSystemType("A");
        node.setAnchorStock("深中华A");
        node.setAnchorMaxBoard(7);
        node.setD0Date(D0);
        node.setStatus(STATUS_PENDING);
        return node;
    }

    /** 已知答案那天的全部原料，读数按库里实际明细填。 */
    private static Readings knownDay() {
        Readings r = new Readings();
        r.d0 = D0;
        r.t1 = T1;
        r.anchorInput = "深中华A";
        r.anchorMatched = true;
        r.anchorCode = "000017";
        r.anchorName = "深中华A";
        r.sector = "饰品";
        r.limitUpCount = 88;
        r.limitDownCount = 11;
        // [D0, D0-1, D0-2, D0-3]＝6,7,6,5：只降一天，不算连续压缩
        r.heights = new ArrayList<>(Arrays.asList(6, 7, 6, 5));
        r.candidates = new ArrayList<>(Arrays.asList(
                second("000560", "我爱我家", "房地产服"),
                second("002679", "福建金森", "林业Ⅱ"),
                second("003005", "竞业达", "IT服务Ⅱ"),
                second("600551", "时代出版", "出版"),
                second("601086", "国芳集团", "一般零售"),
                second("000011", "深物业A", "房地产开"),
                second("600227", "赤天化", "农化制品"),
                second("600691", "潞化科技", "农化制品"),
                second("600722", "金牛化工", "化学原料"),
                second("603559", "中通国脉", "通信服务")));
        r.t1Boards = boards(new Object[][]{
                {"000560", 3}, {"002679", 3}, {"003005", 3}, {"600551", 3}, {"601086", 3},
                {"002084", 7}, {"002855", 6}});
        r.maxBoards = boards(new Object[][]{
                {"601086", 5}, {"003005", 4}, {"000560", 3}, {"002679", 3}, {"600551", 3}});
        // 老龙 08-28 最后一次涨停（7板），D0 之后 09-03 才进跌停池
        r.anchorRows = new ArrayList<>(Arrays.asList(
                limit("000017", "深中华A", LocalDate.of(2026, 8, 28), 7, "饰品"),
                down("000017", "深中华A", LocalDate.of(2026, 9, 3))));
        return r;
    }

    private static Map<String, Integer> boards(Object[][] pairs) {
        Map<String, Integer> map = new TreeMap<>();
        for (Object[] pair : pairs) {
            map.put((String) pair[0], (Integer) pair[1]);
        }
        return map;
    }

    private static MarketStock second(String code, String name, String industry) {
        return limit(code, name, D0, 2, industry);
    }

    private static MarketStock first(String code, String name) {
        return limit(code, name, D0, 1, "饰品");
    }

    private static MarketStock limit(String code, String name, LocalDate date, int board, String industry) {
        MarketStock row = new MarketStock();
        row.setTradeDate(date);
        row.setCode(code);
        row.setName(name);
        row.setPool(MarketStock.POOL_LIMIT_UP);
        row.setConsecutive(board);
        row.setIndustry(industry);
        return row;
    }

    private static MarketStock down(String code, String name, LocalDate date) {
        MarketStock row = new MarketStock();
        row.setTradeDate(date);
        row.setCode(code);
        row.setName(name);
        row.setPool(MarketStock.POOL_LIMIT_DOWN);
        row.setIndustry("饰品");
        return row;
    }

    private static NodeSuggestVO decide(NodeEvent node, Readings r) {
        return NodeSuggestService.decide(node, r, JSON);
    }

    // ---------- 已知答案 ----------

    @Test
    void 深中华A样本复算出他文档里那组数() {
        NodeSuggestVO vo = decide(shenZhonghuaA(), knownDay());
        assertEquals(10, vo.getPromotion().getTotal().intValue());
        assertEquals(5, vo.getT1PromotionCount().intValue());
        assertEquals(new BigDecimal("50.00"), vo.getT1PromotionRate());
        assertEquals(0, vo.getT1AnchorRepack().intValue());
        assertEquals(STATUS_VALID, vo.getSuggestedStatus());
        assertEquals(1, vo.getNodeValid().intValue());
        // 节点票取晋级票里 D0 之后最高板最大的那只：国芳集团 5 板 > 竞业达 4 板
        assertEquals("国芳集团(601086)", vo.getNodeStock());
        assertEquals(5, vo.getNodeStockMaxBoard().intValue());
        assertTrue(vo.isReady());
        assertTrue(vo.getReason().contains("50.00%"), vo.getReason());
        assertTrue(vo.getReason().contains("强节点"), vo.getReason());
    }

    @Test
    void 跌停家数与文档叙述不符时如实报读数不挑一个数() {
        NodeSuggestVO vo = decide(shenZhonghuaA(), knownDay());
        // 库里 08-31 是 11 家，他文档写的是 8 家。判据按读数走，来源写在 source 里由他自己核
        NodeSuggestVO.FilterItem limitDown = vo.getFilter().get(0);
        assertEquals("11 家", limitDown.getActual());
        assertEquals(Boolean.FALSE, limitDown.getPass());
        assertEquals(Boolean.FALSE, vo.getFilterAllPass());
        assertTrue(limitDown.getSource().contains("limit_down_count"), limitDown.getSource());
        // 过滤器不过不改状态：§八 那条讲的是"该不该下手"，状态讲的是"节点成不成立"
        assertEquals(STATUS_VALID, vo.getSuggestedStatus());
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("前置过滤器未全过")),
                vo.getWarnings().toString());
    }

    @Test
    void 老龙D0之后跌停只提示不越权改状态() {
        NodeSuggestVO vo = decide(shenZhonghuaA(), knownDay());
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("2026-09-03")
                && w.contains("老龙A杀")), vo.getWarnings().toString());
        assertEquals(STATUS_VALID, vo.getSuggestedStatus());
    }

    @Test
    void 连板高度要连降三天才算压缩() {
        assertEquals(Boolean.FALSE, NodeSuggestService.heightCompressed(Arrays.asList(6, 7, 6, 5)));
        assertEquals(Boolean.TRUE, NodeSuggestService.heightCompressed(Arrays.asList(3, 4, 5, 6)));
        // 少一个读数只能报未知；兜成"没压缩"等于把缺数讲成过滤器通过
        assertNull(NodeSuggestService.heightCompressed(Arrays.asList(3, 4, 5)));
        assertNull(NodeSuggestService.heightCompressed(Arrays.asList(3, null, 5, 6)));
    }

    // ---------- 判据分支 ----------

    @Test
    void 老龙反包即作废() {
        Readings r = knownDay();
        r.anchorRows.add(limit("000017", "深中华A", T1, 1, "饰品"));
        NodeSuggestVO vo = decide(shenZhonghuaA(), r);
        assertEquals(1, vo.getT1AnchorRepack().intValue());
        assertEquals(STATUS_INVALID, vo.getSuggestedStatus());
        assertEquals(0, vo.getNodeValid().intValue());
        assertTrue(vo.getReason().contains("老龙反包"), vo.getReason());
    }

    @Test
    void 晋级零只是失效且不写节点票() {
        Readings r = knownDay();
        r.t1Boards = boards(new Object[][]{{"002084", 7}});
        r.maxBoards = boards(new Object[][]{});
        NodeSuggestVO vo = decide(shenZhonghuaA(), r);
        assertEquals(0, vo.getT1PromotionCount().intValue());
        assertEquals(STATUS_INVALID, vo.getSuggestedStatus());
        assertTrue(vo.getNodeStock().isEmpty(), vo.getNodeStock());
        assertEquals(0, vo.getNodeStockMaxBoard().intValue());
    }

    @Test
    void 数量和率两条都压线才算有效() {
        Readings r = knownDay();
        // 3/10＝30.00%：≥3 只与 ≥30% 同时取等号
        r.t1Boards = boards(new Object[][]{{"000560", 3}, {"002679", 3}, {"003005", 3}});
        assertEquals(STATUS_VALID, decide(shenZhonghuaA(), r).getSuggestedStatus());
        // 2/10＝20%：数量先不够
        r.t1Boards = boards(new Object[][]{{"000560", 3}, {"002679", 3}});
        assertEquals(STATUS_PENDING, decide(shenZhonghuaA(), r).getSuggestedStatus());
        // 率很高但数量不够：2/3＝66.67% 远超 30%，§三 那道硬门槛是"≥3 只" → 待验证
        r.candidates = new ArrayList<>(r.candidates.subList(0, 3));
        NodeSuggestVO vo = decide(shenZhonghuaA(), r);
        assertEquals(3, vo.getPromotion().getTotal().intValue());
        assertEquals(new BigDecimal("66.67"), vo.getT1PromotionRate());
        assertEquals(STATUS_PENDING, vo.getSuggestedStatus());
    }

    @Test
    void 系统B看板块一进二且不看老龙反包() {
        NodeEvent node = shenZhonghuaA();
        node.setSystemType("B");
        Readings r = knownDay();
        r.candidates = new ArrayList<>(Arrays.asList(
                first("002721", "金一文化"), first("603900", "莱绅通灵"), first("601000", "唐钢股份")));
        r.t1Boards = boards(new Object[][]{{"002721", 2}, {"603900", 2}});
        r.maxBoards = boards(new Object[][]{{"002721", 4}, {"603900", 2}});
        // 老龙 T+1 反包：系统A 一票否决，系统B 那张表里没有这条
        r.anchorRows.add(limit("000017", "深中华A", T1, 1, "饰品"));
        NodeSuggestVO vo = decide(node, r);
        assertEquals(2, vo.getT1PromotionCount().intValue());
        assertEquals(STATUS_VALID, vo.getSuggestedStatus());
        assertEquals("B", vo.getSystemType());
        // 只有一只晋级 → 不够 §四 的 ≥2 只
        r.t1Boards = boards(new Object[][]{{"002721", 2}});
        assertEquals(STATUS_PENDING, decide(node, r).getSuggestedStatus());
    }

    // ---------- 缺数绝不兜底 ----------

    @Test
    void T加1没明细时晋级数是空的而不是零只() {
        Readings r = knownDay();
        r.t1Boards = null;
        NodeSuggestVO vo = decide(shenZhonghuaA(), r);
        assertNull(vo.getT1PromotionCount());
        assertNull(vo.getT1PromotionRate());
        assertNull(vo.getPromotion().getRate());
        assertNull(vo.getT1AnchorRepack());
        assertFalse(vo.isReady());
        assertEquals(STATUS_PENDING, vo.getSuggestedStatus());
        assertTrue(vo.getReason().startsWith("判据不齐"), vo.getReason());
    }

    @Test
    void 判不了永远不ready哪怕取数侧一句都没说() {
        // 直接手搓一份"老龙没匹配上、但没写缺数说明"的原料，验的是不变式而不是某句文案
        Readings r = knownDay();
        r.anchorMatched = false;
        r.anchorRows = new ArrayList<>();
        NodeSuggestVO vo = decide(shenZhonghuaA(), r);
        assertFalse(vo.isReady());
        // 老龙判不了只让"反包"这一格空着；T+1 晋级 5 只是真的读数，没理由跟着一起抹掉
        assertNull(vo.getT1AnchorRepack());
        assertEquals(5, vo.getT1PromotionCount().intValue());
        assertTrue(vo.getMissing().get(0).contains("判不了"), vo.getMissing().toString());
        assertEquals(STATUS_PENDING, vo.getSuggestedStatus());
    }

    @Test
    void 候选池是空的说的是分母空而不是零只晋级() {
        Readings r = knownDay();
        String text = NodeSuggestService.emptyPoolMessage(r, false);
        assertTrue(text.contains("候选池是空的"), text);
        assertFalse(text.contains("0 只"), text);
        r.candidates = new ArrayList<>();
        r.missing.add(text);
        NodeSuggestVO vo = decide(shenZhonghuaA(), r);
        assertFalse(vo.isReady());
        assertEquals(STATUS_PENDING, vo.getSuggestedStatus());
    }

    @Test
    void D0没填时什么都不判() {
        NodeEvent node = shenZhonghuaA();
        node.setD0Date(null);
        Readings r = new Readings();
        r.missing.add("D0 日期未填：没有断板日，整套判据一条都判不起来");
        NodeSuggestVO vo = decide(node, r);
        assertFalse(vo.isReady());
        assertEquals(STATUS_PENDING, vo.getSuggestedStatus());
        assertTrue(vo.getFingerprint().isEmpty());
    }

    @Test
    void 家数缺读时过滤器整体是未知() {
        Readings r = knownDay();
        r.limitDownCount = null;
        NodeSuggestVO vo = decide(shenZhonghuaA(), r);
        assertNull(vo.getFilter().get(0).getPass());
        assertEquals("无读数", vo.getFilter().get(0).getActual());
        assertNull(vo.getFilterAllPass());
        assertTrue(vo.isReady());
    }

    // ---------- 指纹 ----------

    @Test
    void 盘面一变指纹就变采纳当场被拒() {
        NodeSuggestVO seen = decide(shenZhonghuaA(), knownDay());
        assertTrue(NodeSuggestService.diff(JSON, seen.getFingerprint(), seen).isEmpty());

        Readings changed = knownDay();
        changed.t1Boards = boards(new Object[][]{
                {"000560", 3}, {"002679", 3}, {"003005", 3}, {"600551", 3}, {"601086", 3}, {"000011", 3}});
        changed.maxBoards = boards(new Object[][]{
                {"601086", 5}, {"003005", 4}, {"000560", 3}, {"002679", 3}, {"600551", 3}, {"000011", 3}});
        NodeSuggestVO now = decide(shenZhonghuaA(), changed);
        List<String> diffs = NodeSuggestService.diff(JSON, seen.getFingerprint(), now);
        // 数与率两个都动了；报的是"哪一格从几变到几"，不是干巴巴一句"不一致"
        assertEquals(2, diffs.size(), diffs.toString());
        assertTrue(diffs.get(0).contains("晋级数量：5 → 现在算出 6"), diffs.toString());
    }

    @Test
    void 指纹不认识的字段一律按不一致处理() {
        NodeSuggestVO fresh = decide(shenZhonghuaA(), knownDay());
        assertEquals(8, NodeSuggestService.diff(JSON, "{}", fresh).size());
        assertEquals(8, NodeSuggestService.diff(JSON, null, fresh).size());
    }

    @Test
    void 状态从有效漂到失效时指纹认得出来() {
        NodeSuggestVO valid = decide(shenZhonghuaA(), knownDay());
        Readings repack = knownDay();
        repack.anchorRows.add(limit("000017", "深中华A", T1, 1, "饰品"));
        NodeSuggestVO invalid = decide(shenZhonghuaA(), repack);
        List<String> diffs = NodeSuggestService.diff(JSON, valid.getFingerprint(), invalid);
        assertTrue(diffs.get(0).contains("状态：有效 → 现在算出 失效"), diffs.toString());
    }

    @Test
    void 晋级率按四舍五入留两位() {
        assertEquals(new BigDecimal("33.33"), NodeSuggestService.percentage(1, 3));
        assertEquals(new BigDecimal("50.00"), NodeSuggestService.percentage(5, 10));
        assertEquals(new BigDecimal("16.67"), NodeSuggestService.percentage(1, 6));
    }

    @Test
    void 系统类型没填时按A判并说出来() {
        NodeEvent node = shenZhonghuaA();
        node.setSystemType("");
        NodeSuggestVO vo = decide(node, knownDay());
        assertEquals("A", vo.getSystemType());
        assertTrue(vo.getWarnings().get(0).contains("按系统A"), vo.getWarnings().toString());
    }

    @Test
    void 模糊匹配到老龙时并排出他写的和匹配到的() {
        Readings r = knownDay();
        r.anchorFuzzy = true;
        r.anchorInput = "深中华";
        NodeSuggestVO vo = decide(shenZhonghuaA(), r);
        assertEquals("深中华", vo.getAnchor().getInput());
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("「深中华」")
                && w.contains("深中华A(000017)")), vo.getWarnings().toString());
    }

    @Test
    void 来路一句话落在status_note宽度内且不带null() {
        NodeSuggestVO vo = decide(shenZhonghuaA(), knownDay());
        assertTrue(vo.getReason().length() < 295, "长度 " + vo.getReason().length());
        assertFalse(vo.getReason().contains("null"), vo.getReason());
        assertFalse(vo.getFingerprint().contains("null"), vo.getFingerprint());
    }
}
