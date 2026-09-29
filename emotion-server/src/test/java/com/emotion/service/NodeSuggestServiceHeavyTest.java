package com.emotion.service;

import static com.emotion.service.NodeSuggestService.STATUS_PENDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.emotion.service.NodeSuggestService.CandidatePool;
import com.emotion.service.NodeSuggestService.Readings;
import com.emotion.vo.NodeSuggestVO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 接位候选池跟着<b>老龙的放量日</b>走，不跟着断板日走。
 *
 * <p>他原话：高低切本质是资金高低切，资金什么时候从上面下来，看的是老龙放量那一天。所以
 * 「断板当天取二板」只是<b>放量发生在断板前一日</b>时的一种巧合——那天的首板到 D0 正好是二板。
 * 两例都取自生产库 {@code t_market_stock} 的真实成交额，一条对一条：
 * <ul>
 *   <li>深中华Ａ 000017：08-28 七板 12.53 亿 ÷ 08-27 6.06 亿＝2.07 倍 → 钱在前一日就下来了 → D0 二板照旧；
 *       而 08-28 的首板国芳集团到 08-31 正是二板，两种数法数到同一批票。</li>
 *   <li>华瓷股份 001216：09-22 0.48 亿 ÷ 09-21 0.51 亿＝0.95 倍（缩量），09-23 炸板当天 16.26 亿才放量
 *       → 候选挪到 09-23、板数取 1。</li>
 * </ul>
 *
 * <p>第三、第四条是这套判据的分寸：窗口内一天都没放量＝资金还没下来，接位对象无从谈起，
 * <b>这是缺数一样的不许采纳，不是"那就照旧取二板"</b>；而老龙断板前后压根没有成交额记录＝判不了，
 * 退回原口径但必须在界面上出声——8 月上旬那段 amount 大面积缺失，卡住会让整页历史节点都不可采纳。
 */
class NodeSuggestServiceHeavyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate D0_HUA = LocalDate.of(2026, 9, 23);
    private static final LocalDate D0_SHEN = LocalDate.of(2026, 8, 31);

    /** 09-23 起往后五个交易日：窗口按交易日数，不是自然日。 */
    private static final List<LocalDate> WINDOW_0923 = Arrays.asList(
            LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25),
            LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29));

    // ---------- 两种正解 ----------

    /** 华瓷型：断板前一日是缩量，炸板当天才放量 → 候选＝那天的首板，板数从 2 变成 1。 */
    @Test
    void 断板当天才放量就取那天的首板() {
        CandidatePool pool = NodeSuggestService.pickCandidatePool(D0_HUA, huaCi(), WINDOW_0923);

        assertNull(pool.missing, pool.basis);
        assertFalse(pool.fallback, pool.basis);
        assertEquals(D0_HUA, pool.date);
        assertEquals(Integer.valueOf(1), pool.board);
        assertEquals("2026-09-23 首板", pool.label());
        // 来路必须把两次比较都讲清：前一日没放量（0.48 对 0.51），到这天才下来（0.48 → 16.26）
        assertTrue(pool.basis.contains("0.95"), pool.basis);
        assertTrue(pool.basis.contains("16.26"), pool.basis);
        assertTrue(pool.basis.contains("33.59"), pool.basis);
    }

    /** 深中华型：钱在断板前一日已经下来 → 现行为一字不改，候选仍是 D0 的二连板。 */
    @Test
    void 前一日已放量时照旧取断板日二板() {
        CandidatePool pool = NodeSuggestService.pickCandidatePool(D0_SHEN, shenZhonghua(),
                Arrays.asList(D0_SHEN, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
                        LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 7)));

        assertNull(pool.missing, pool.basis);
        assertFalse(pool.fallback, pool.basis);
        assertEquals(D0_SHEN, pool.date);
        assertEquals(Integer.valueOf(2), pool.board);
        assertTrue(pool.basis.contains("2.07"), pool.basis);
    }

    // ---------- 一条判据的两条边界 ----------

    /** 他文档里「老龙头/高位放量大面」：出货那天的量一样算资金下来，不看它是不是跌停池。 */
    @Test
    void 跌停日放出的量一样算放量() {
        List<MarketStock> rows = new ArrayList<>(Arrays.asList(
                up("600000", "老龙", LocalDate.of(2026, 5, 6), 6, "6.00"),
                up("600000", "老龙", LocalDate.of(2026, 5, 7), 7, "6.20"),
                down("600000", "老龙", LocalDate.of(2026, 5, 12), "3.00"),
                down("600000", "老龙", LocalDate.of(2026, 5, 13), "62.00")));
        CandidatePool pool = NodeSuggestService.pickCandidatePool(LocalDate.of(2026, 5, 8), rows,
                Arrays.asList(LocalDate.of(2026, 5, 8), LocalDate.of(2026, 5, 11),
                        LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 13)));

        assertEquals(LocalDate.of(2026, 5, 13), pool.date, pool.basis);
        assertEquals(Integer.valueOf(1), pool.board);
    }

    /** 缩量不是放量，微幅放量也不算：阈值以下窗口内一个都不认，交给缺数那一支。 */
    @Test
    void 整窗口没放量时不给候选池() {
        List<MarketStock> flat = new ArrayList<>(Arrays.asList(
                up("001216", "华瓷股份", LocalDate.of(2026, 9, 15), 1, "1.00"),
                up("001216", "华瓷股份", LocalDate.of(2026, 9, 16), 2, "1.10"),
                up("001216", "华瓷股份", LocalDate.of(2026, 9, 17), 3, "1.05"),
                up("001216", "华瓷股份", LocalDate.of(2026, 9, 18), 4, "1.20")));
        flat.add(up("001216", "华瓷股份", LocalDate.of(2026, 9, 21), 5, "1.15"));
        flat.add(up("001216", "华瓷股份", LocalDate.of(2026, 9, 22), 6, "1.25"));
        flat.add(zb("001216", "华瓷股份", LocalDate.of(2026, 9, 23), "1.30"));
        flat.add(up("001216", "华瓷股份", LocalDate.of(2026, 9, 24), 1, "1.35"));
        flat.add(up("001216", "华瓷股份", LocalDate.of(2026, 9, 25), 2, "1.28"));
        flat.add(up("001216", "华瓷股份", LocalDate.of(2026, 9, 28), 3, "1.40"));
        flat.add(up("001216", "华瓷股份", LocalDate.of(2026, 9, 29), 4, "1.32"));

        CandidatePool none = NodeSuggestService.pickCandidatePool(
                LocalDate.of(2026, 9, 19), flat, WINDOW_0923);

        assertNull(none.date);
        assertNull(none.board);
        assertNotNull(none.missing);
        assertTrue(none.missing.contains("资金还没从上面下来"), none.missing);
        assertTrue(none.missing.contains("接位对象无从谈起"), none.missing);
    }

    // ---------- 判不了 ≠ 没放量 ----------

    /** 08-20/08-21 库里 amount 是 NULL：老龙有成交记录里最早的那天没有前一笔可比，判不了就是判不了。 */
    @Test
    void 成交额缺失时退回原口径但要出声() {
        CandidatePool pool = NodeSuggestService.pickCandidatePool(LocalDate.of(2026, 8, 25),
                shenZhonghua(), Arrays.asList(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 26)));

        assertTrue(pool.fallback, pool.basis);
        assertEquals(LocalDate.of(2026, 8, 25), pool.date);
        assertEquals(Integer.valueOf(2), pool.board);
        assertTrue(pool.basis.contains("判不了"), pool.basis);
        assertTrue(pool.basis.contains("这是兜底，不是判出来的结论"), pool.basis);
    }

    /** 兜底那条在建议里看得见也采纳得了。 */
    @Test
    void 兜底那条在建议里看得见也采纳得了() {
        Readings r = readings(fallbackPool(D0_HUA, NodeSuggestService.A_CANDIDATE_BOARD,
                "老龙在 2026-09-23 之前没有任何带成交额的盘面记录——候选退回原口径取 2026-09-23 的二连板。"
                        + "这是兜底，不是判出来的结论"));
        NodeSuggestVO vo = NodeSuggestService.decide(huaCiNode(), r, JSON);

        assertTrue(vo.isReady(), vo.getMissing().toString());
        assertEquals("2026-09-23 二连板", vo.getCandidatePool());
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("放没放量判不了")
                && w.contains("兜底")), vo.getWarnings().toString());
    }

    private static CandidatePool fallbackPool(LocalDate date, int board, String basis) {
        CandidatePool pool = new CandidatePool();
        pool.date = date;
        pool.board = board;
        pool.fallback = true;
        pool.basis = basis;
        return pool;
    }

    // ---------- 候选池进了结论的哪几格 ----------

    /** 池子标签同时进 reason、VO 三格与采纳指纹：他点的采纳落的就是看到的那个池子。 */
    @Test
    void 华瓷型池子写进建议与指纹() {
        CandidatePool pool = NodeSuggestService.pickCandidatePool(D0_HUA, huaCi(), WINDOW_0923);
        NodeSuggestVO vo = NodeSuggestService.decide(huaCiNode(), readings(pool), JSON);

        assertEquals(LocalDate.of(2026, 9, 23), vo.getCandidateDate());
        assertEquals(Integer.valueOf(1), vo.getCandidateBoard());
        assertEquals("2026-09-23 首板", vo.getCandidatePool());
        assertTrue(vo.getHeavyBasis().contains("候选取 2026-09-23 的首板"), vo.getHeavyBasis());
        assertTrue(vo.getReason().contains("2026-09-23 首板 3 只晋级 1 只"), vo.getReason());
        assertTrue(vo.getFingerprint().contains("\"candidatePool\":\"2026-09-23 首板\""),
                vo.getFingerprint());
        // 3 只首板 1 只晋级＝33.33%：够不上 §三 的 ≥30% 且 ≥3 只，这条待验证
        assertEquals(new BigDecimal("33.33"), vo.getT1PromotionRate());
        assertEquals(STATUS_PENDING, vo.getSuggestedStatus());
        // 池子一挪，节点票跟着换：库里真实 39 只首板里按同一口径挑出的就是福建水泥
        assertTrue(vo.getNodeStock().contains("福建水泥"), vo.getNodeStock());
    }

    /** 窗口内没放量 ⇒ 没有候选池 ⇒ 缺数，不许采纳：这条不是"晋级 0 只所以失效"。 */
    @Test
    void 没有放量日就不给结论() {
        CandidatePool none = new CandidatePool();
        none.missing = "老龙断板前一日没放量，往后 5 个交易日也没有一天放量——资金还没从上面下来";
        Readings r = readings(none);
        r.missing.add(none.missing);
        r.candidates = new ArrayList<>();
        r.t1Boards = null;
        NodeSuggestVO vo = NodeSuggestService.decide(huaCiNode(), r, JSON);

        assertFalse(vo.isReady());
        assertTrue(vo.getMissing().stream().anyMatch(m -> m.contains("资金还没从上面下来")),
                vo.getMissing().toString());
        assertEquals("", vo.getCandidatePool());
    }

    // ---------- fixture ----------

    /** 深中华Ａ 000017 在库里的真实成交额：08-20/08-21 那两天有行但 amount 是 NULL。 */
    private static List<MarketStock> shenZhonghua() {
        List<MarketStock> rows = new ArrayList<>(Arrays.asList(
                nullAmountUp("000017", "深中华A", LocalDate.of(2026, 8, 20), 1),
                nullAmountUp("000017", "深中华A", LocalDate.of(2026, 8, 21), 2)));
        rows.add(up("000017", "深中华A", LocalDate.of(2026, 8, 24), 3, "0.69120996"));
        rows.add(up("000017", "深中华A", LocalDate.of(2026, 8, 25), 4, "4.9748392"));
        rows.add(up("000017", "深中华A", LocalDate.of(2026, 8, 26), 5, "9.61828272"));
        rows.add(up("000017", "深中华A", LocalDate.of(2026, 8, 27), 6, "6.05589536"));
        rows.add(up("000017", "深中华A", LocalDate.of(2026, 8, 28), 7, "12.53143472"));
        return rows;
    }

    /** 华瓷股份 001216：一路五板六板的量都在缩，09-23 炸板当天 16.26 亿才把量放出来。 */
    private static List<MarketStock> huaCi() {
        List<MarketStock> rows = new ArrayList<>(Arrays.asList(
                up("001216", "华瓷股份", LocalDate.of(2026, 9, 15), 1, "0.7494148"),
                up("001216", "华瓷股份", LocalDate.of(2026, 9, 16), 2, "0.75532907"),
                up("001216", "华瓷股份", LocalDate.of(2026, 9, 17), 3, "5.52648544"),
                up("001216", "华瓷股份", LocalDate.of(2026, 9, 18), 4, "4.96116528"),
                up("001216", "华瓷股份", LocalDate.of(2026, 9, 21), 5, "0.51219505")));
        rows.add(up("001216", "华瓷股份", LocalDate.of(2026, 9, 22), 6, "0.48414477"));
        rows.add(zb("001216", "华瓷股份", LocalDate.of(2026, 9, 23), "16.26331872"));
        return rows;
    }

    /** 09-23 那天涨停池里的首板只挑三只，够把晋级率算成 33.33% 就停：分母真值是 39 只，这里比的是口径不是家数。 */
    private static Readings readings(CandidatePool pool) {
        Readings r = new Readings();
        r.d0 = D0_HUA;
        r.pool = pool;
        r.t1 = LocalDate.of(2026, 9, 24);
        r.anchorInput = "华瓷股份";
        r.anchorMatched = true;
        r.anchorCode = "001216";
        r.anchorName = "华瓷股份";
        r.sector = "家居用品";
        r.limitUpCount = 71;
        r.limitDownCount = 9;
        r.heights = new ArrayList<>(Arrays.asList(6, 7, 6, 6));
        LocalDate poolDate = pool.date == null ? D0_HUA : pool.date;
        r.candidates = new ArrayList<>(Arrays.asList(
                first(poolDate, "002119", "康强电子"),
                first(poolDate, "002819", "东方中科"),
                first(poolDate, "600802", "福建水泥")));
        r.t1Boards = boards(new Object[][]{{"600802", 2}, {"002396", 5}});
        r.maxBoards = boards(new Object[][]{{"600802", 3}, {"002119", 2}});
        r.anchorRows = new ArrayList<>(Arrays.asList(
                up("001216", "华瓷股份", LocalDate.of(2026, 9, 22), 6, "0.48414477")));
        return r;
    }

    private static NodeEvent huaCiNode() {
        NodeEvent node = new NodeEvent();
        node.setId(967L);
        node.setUserId(2L);
        node.setSystemType("A");
        node.setAnchorStock("华瓷股份");
        node.setAnchorMaxBoard(6);
        node.setD0Date(D0_HUA);
        node.setStatus(STATUS_PENDING);
        return node;
    }

    private static Map<String, Integer> boards(Object[][] pairs) {
        Map<String, Integer> map = new TreeMap<>();
        for (Object[] pair : pairs) {
            map.put((String) pair[0], (Integer) pair[1]);
        }
        return map;
    }

    private static MarketStock first(LocalDate day, String code, String name) {
        MarketStock row = new MarketStock();
        row.setTradeDate(day);
        row.setCode(code);
        row.setName(name);
        row.setPool(MarketStock.POOL_LIMIT_UP);
        row.setConsecutive(1);
        row.setIndustry("家居用品");
        return row;
    }

    /** 成交额库里存的是元，fixture 里按亿写更易对库。 */
    private static MarketStock up(String code, String name, LocalDate date, int board, String yi) {
        MarketStock row = new MarketStock();
        row.setTradeDate(date);
        row.setCode(code);
        row.setName(name);
        row.setPool(MarketStock.POOL_LIMIT_UP);
        row.setConsecutive(board);
        row.setIndustry("饰品");
        row.setAmount(new BigDecimal(yi).multiply(new BigDecimal("100000000")));
        return row;
    }

    private static MarketStock nullAmountUp(String code, String name, LocalDate date, int board) {
        MarketStock row = up(code, name, date, board, "0");
        row.setAmount(null);
        return row;
    }

    private static MarketStock zb(String code, String name, LocalDate date, String yi) {
        MarketStock row = up(code, name, date, 0, yi);
        row.setPool(MarketStock.POOL_BROKEN);
        row.setConsecutive(null);
        return row;
    }

    private static MarketStock down(String code, String name, LocalDate date, String yi) {
        MarketStock row = up(code, name, date, 0, yi);
        row.setPool(MarketStock.POOL_LIMIT_DOWN);
        row.setConsecutive(null);
        return row;
    }
}
