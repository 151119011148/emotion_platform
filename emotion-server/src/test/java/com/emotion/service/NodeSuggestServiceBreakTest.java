package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.NodeEvent;
import com.emotion.mapper.NodeEventMapper;
import com.emotion.vo.BreakDetailVO;
import com.emotion.vo.NodeSuggestVO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 复算面板的破壁分支（{@link NodeSuggestService#decideBreak}）：试探行看次日续没续板，
 * 成功行看这天还是不是 ★。全内存 fixture，不碰 DB / Spring——判定那半边是纯函数。
 *
 * <p>这一族最要命的错误只有一个：<b>把"判不了"讲成一个看起来正常的结论</b>。
 * 所以三条"空"必须各钉各的：
 * <ol>
 *   <li>曲线上没有点＝那天没行情 → 缺数，<b>不许采纳</b>；</li>
 *   <li>次日明细还没落库＝续没续板还不知道 → 缺数，<b>不许采纳</b>；</li>
 *   <li>有点但 ☆/★ 没了＝他立的那一行前提真的不成立了 → <b>建议作废，由他点</b>。</li>
 * </ol>
 * 前两条兜成"失效"会把没拉过行情的日子判死，第三条兜成"判不了"会让一张早就该作废的横行不掉。
 *
 * <p>另一条纪律：助攻、盘口、情绪闸门按他定的口径<b>只出声不否决</b>——一条都不进 {@code missing}，
 * 所以"助攻只有 1 只"这一格必须仍然 ready，而"次日没明细"这一格必须不 ready。
 */
class NodeSuggestServiceBreakTest {

    private static final Long USER = 1L;
    private static final LocalDate D0 = LocalDate.of(2026, 8, 28);
    private static final LocalDate T1 = LocalDate.of(2026, 8, 31);
    private static final String SHEN = "000017";

    // ---------- 试探行：次日续板判定 ----------

    /** 次日续板＝这次试探兑现了：状态转有效、续板判定转 SUCCESS，节点票就是追线的那只。 */
    @Test
    void continuationMakesTheProbeRowValid() {
        NodeSuggestVO vo = NodeSuggestService.decideBreak(probeRow(),
                readings(probe(T1, BreakDetailVO.OUTCOME_SUCCESS, "8-31 续板到 8 板")), new ObjectMapper());

        assertEquals(NodeSuggestService.STATUS_VALID, vo.getStatus());
        assertEquals(BreakDetailVO.OUTCOME_SUCCESS, vo.getRepairStatus());
        assertEquals("续板成功", vo.getRepairStatusLabel());
        assertEquals(Integer.valueOf(1), vo.getNodeValid());
        assertEquals(T1, vo.getT1Date());
        assertTrue(vo.isReady());
        assertNull(vo.getSystemType(), "破壁节点既不是系统A 也不是系统B");
        assertTrue(vo.getReason().contains("续板成功"), vo.getReason());
        assertTrue(vo.getReason().contains("追 6 板破壁线"), vo.getReason());
    }

    /** 次日掉榜＝没兑现：失效，理由写的是"未续板"，不是高低切那套"晋级清零"。 */
    @Test
    void stallFailsTheProbeRow() {
        NodeSuggestVO vo = NodeSuggestService.decideBreak(probeRow(),
                readings(probe(T1, BreakDetailVO.OUTCOME_FAILED, "8-31 掉出连板名单")), new ObjectMapper());

        assertEquals(NodeSuggestService.STATUS_INVALID, vo.getStatus());
        assertEquals(BreakDetailVO.OUTCOME_FAILED, vo.getRepairStatus());
        assertEquals("未续板", vo.getRepairStatusLabel());
        assertEquals("未续板失效", vo.getConclusionReason());
        assertEquals(Integer.valueOf(0), vo.getNodeValid());
        assertTrue(vo.isReady());
    }

    /**
     * 次日明细还没拉：续没续板这件事<b>还不知道</b>。
     *
     * <p>这一格必须卡住采纳——他文档里的口径是"次日续板才算破壁成功"，
     * 判不了就出个"有效/失效"，等于替盘面提前作答。
     */
    @Test
    void unsettledNextDayIsNotAdoptable() {
        NodeSuggestVO vo = NodeSuggestService.decideBreak(probeRow(),
                readings(probe(null, BreakDetailVO.OUTCOME_PENDING, "次一交易日的明细还没落库")),
                new ObjectMapper());

        assertFalse(vo.isReady());
        assertEquals(NodeSuggestService.STATUS_PENDING, vo.getStatus());
        assertEquals("待判定", vo.getRepairStatusLabel());
        assertTrue(vo.getMissing().stream().anyMatch(m -> m.contains("还没落库")), String.valueOf(vo.getMissing()));
    }

    /** outcome 整格为空（面板都没算出次日）与"算了但结果是待定"是同一种缺数，不许一个报缺一个报失效。 */
    @Test
    void missingOutcomeIsAlsoNotAdoptable() {
        BreakDetailVO d = probe(T1, BreakDetailVO.OUTCOME_SUCCESS, "续板");
        d.setOutcome(null);

        NodeSuggestVO vo = NodeSuggestService.decideBreak(probeRow(), readings(d), new ObjectMapper());

        assertFalse(vo.isReady());
        assertTrue(vo.getMissing().stream().anyMatch(m -> m.contains("次一交易日")), String.valueOf(vo.getMissing()));
    }

    // ---------- 三条"空"分开 ----------

    /** 曲线上压根没有这一天＝那天没有涨停明细：是判不起来，不是"这次破壁失败了"。 */
    @Test
    void absentCurvePointIsMissingNotFailed() {
        BreakDetailVO d = new BreakDetailVO();
        d.setTradeDate(D0);
        d.setEvent(BreakDetailVO.EVENT_NONE);
        d.setCurvePoint(Boolean.FALSE);
        d.setDetailMissingReason("这天的连板高度曲线上没有点：那天没有涨停明细，破壁判不起来");

        NodeSuggestVO vo = NodeSuggestService.decideBreak(probeRow(), readings(d), new ObjectMapper());

        assertFalse(vo.isReady());
        assertEquals(NodeSuggestService.STATUS_PENDING, vo.getStatus());
        assertTrue(vo.getMissing().stream().anyMatch(m -> m.contains("曲线上没有点")), String.valueOf(vo.getMissing()));
    }

    /** 曲线有点、但这天的 ☆ 没了：前提真的不成立，建议作废并且<b>可以采纳</b>——作废这件事归他点。 */
    @Test
    void eventGoneFromALiveCurveSuggestsVoidAndLetsHimAdopt() {
        BreakDetailVO d = new BreakDetailVO();
        d.setTradeDate(D0);
        d.setEvent(BreakDetailVO.EVENT_NONE);
        d.setCurvePoint(Boolean.TRUE);

        NodeSuggestVO vo = NodeSuggestService.decideBreak(probeRow(), readings(d), new ObjectMapper());

        assertEquals(NodeSuggestService.STATUS_INVALID, vo.getStatus());
        assertEquals("破壁事件已不成立", vo.getConclusionReason());
        assertTrue(vo.isReady());
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("已经没有 ☆")), String.valueOf(vo.getWarnings()));
    }

    /** 同一个道理用在成功行：★ 没了就是这一行不再成立，warning 说的是 ★ 不是 ☆。 */
    @Test
    void successRowChecksTheStarNotTheProbe() {
        BreakDetailVO d = new BreakDetailVO();
        d.setTradeDate(D0);
        d.setEvent(BreakDetailVO.EVENT_PROBE);
        d.setCurvePoint(Boolean.TRUE);

        NodeSuggestVO vo = NodeSuggestService.decideBreak(breakRow(), readings(d), new ObjectMapper());

        assertEquals(NodeSuggestService.STATUS_INVALID, vo.getStatus());
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("已经没有 ★")), String.valueOf(vo.getWarnings()));
        assertNull(vo.getRepairStatus(), "成功行没有「次日续没续板」这一格");
    }

    /** 07-23 立新能源那种真破壁成功日：这天还是 ★ 就复核通过，不做次日结算。 */
    @Test
    void successRowIsConfirmedWhileTheStarStillStands() {
        BreakDetailVO d = new BreakDetailVO();
        d.setTradeDate(D0);
        d.setEvent(BreakDetailVO.EVENT_BREAK);
        d.setCurvePoint(Boolean.TRUE);
        d.setPrevHigh(6);
        d.setSubject(ref(LI, "立新能源", 7));

        NodeSuggestVO vo = NodeSuggestService.decideBreak(breakRow(), readings(d), new ObjectMapper());

        assertEquals(NodeSuggestService.STATUS_VALID, vo.getStatus());
        assertEquals("破壁成功", vo.getConclusionReason());
        assertEquals(NodeBreakService.TYPE_BREAK, vo.getNodeType());
        assertEquals("破壁成功 · 出手", vo.getNodeTypeLabel());
        assertTrue(vo.isReady());
    }

    private static final String LI = "001258";

    // ---------- 助攻与盘口：只出声，不否决 ----------

    /**
     * 助攻只有 1 只（梯队线是 2 只）＋ 炸板率 60% 的退潮天：这一行<b>照样可以采纳</b>。
     *
     * <p>他定的口径是助攻"只算、只展示"。真样本里 08-28 与 09-22 恰恰是助攻最多、盘最烂的两次都没兑现——
     * 闸门一旦吃了助攻，等于用一个 n=2 的相关性去否决次日续板这个唯一的硬判据。
     */
    @Test
    void weakAssistAndAngryGateOnlySpeak() {
        BreakDetailVO d = probe(T1, BreakDetailVO.OUTCOME_SUCCESS, "8-31 续板到 8 板");
        BreakDetailVO.Assist a = new BreakDetailVO.Assist();
        a.setSameIndustryFirst(1);
        a.setSameIndustrySecond(0);
        a.setSameIndustryThirdPlus(0);
        a.setTotal(1);
        a.setLadderOk(Boolean.FALSE);
        d.setAssist(a);
        BreakDetailVO.BoardInfo b = new BreakDetailVO.BoardInfo();
        b.setPattern("ONE_LINE");
        b.setBreakCount(14);
        b.setTurnoverRate(new BigDecimal("0.74"));
        b.setSealRatio(new BigDecimal("5.35"));
        b.setOneWordKilling(Boolean.TRUE);
        d.setBoard(b);
        d.setGateWarnings(java.util.Arrays.asList("炸板率 60% > 50%", "阶段退潮(强制)"));

        NodeSuggestVO vo = NodeSuggestService.decideBreak(probeRow(), readings(d), new ObjectMapper());

        assertTrue(vo.isReady(), String.valueOf(vo.getMissing()));
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("同属性助攻 首1／二0／3+ 0")),
                String.valueOf(vo.getWarnings()));
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("不进闸门、不改权重")),
                String.valueOf(vo.getWarnings()));
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("一字缩量断魂刀")),
                String.valueOf(vo.getWarnings()));
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.startsWith("情绪闸门｜炸板率")),
                String.valueOf(vo.getWarnings()));
    }

    /** 名义天梯那种助攻全 null 的日子：读不出来就只说读不出来，不兜成"0 只助攻"。 */
    @Test
    void unknownAssistStaysUnknownInWarnings() {
        BreakDetailVO d = probe(T1, BreakDetailVO.OUTCOME_SUCCESS, "续板");
        BreakDetailVO.Assist a = new BreakDetailVO.Assist();
        a.setSameIndustryFirst(null);
        a.setTotal(null);
        d.setAssist(a);
        d.setDetailAvailable(Boolean.FALSE);
        d.setDetailMissingReason("这天只有名义天梯，逐只明细没有");

        NodeSuggestVO vo = NodeSuggestService.decideBreak(probeRow(), readings(d), new ObjectMapper());

        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("同属性助攻 首无／二无／3+ 无，合计 无 只")),
                String.valueOf(vo.getWarnings()));
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("名义天梯")), String.valueOf(vo.getWarnings()));
    }

    // ---------- 指纹：按分支取键 ----------

    /** 破壁行六键，多出"续板判定"、少了高低切那三格：拿九格去比破壁行，每次采纳都会报假的变化。 */
    @Test
    void breakFingerprintCarriesRepairAndDropsSplitColumns() {
        NodeSuggestVO vo = NodeSuggestService.decideBreak(probeRow(),
                readings(probe(T1, BreakDetailVO.OUTCOME_SUCCESS, "续板")), new ObjectMapper());

        List<String> keys = NodeSuggestService.fpKeys(vo);
        assertEquals(6, keys.size(), String.valueOf(keys));
        assertTrue(keys.contains("repairStatus"), String.valueOf(keys));
        assertFalse(keys.contains("t1AnchorRepack"), String.valueOf(keys));
        assertFalse(keys.contains("t1PromotionCount"), String.valueOf(keys));
        assertFalse(keys.contains("t1PromotionRate"), String.valueOf(keys));
        assertTrue(vo.getFingerprint().contains("\"repairStatus\":\"续板成功\""), vo.getFingerprint());
    }

    /** 高低切那九键一个都不许动：破壁分支是加进来的，不是替进去的。 */
    @Test
    void splitFingerprintKeepsItsNineKeys() {
        NodeSuggestVO vo = new NodeSuggestVO();
        vo.setNodeType(NodeSuggestService.TYPE_FILL_SAME);

        List<String> keys = NodeSuggestService.fpKeys(vo);

        assertEquals(9, keys.size(), String.valueOf(keys));
        assertTrue(keys.contains("t1AnchorRepack"), String.valueOf(keys));
        assertFalse(keys.contains("repairStatus"), String.valueOf(keys));
    }

    /** {@code node_type} 为 NULL 的存量行一律走高低切：不能因为新加了一族就把老账拖进破壁判定。 */
    @Test
    void legacyRowsWithoutTypeStayOnTheSplitPath() {
        assertFalse(NodeBreakService.isBreakType(null));
        assertFalse(NodeBreakService.isBreakType("SPLIT_PENDING"));
        assertTrue(NodeBreakService.isBreakType(NodeBreakService.TYPE_PROBE));
        assertTrue(NodeBreakService.isBreakType(NodeBreakService.TYPE_BREAK));
    }

    /** diff 里那一句要他看得懂：报的是"待判定 → 现在算出 未续板"，不是一个字段名。 */
    @Test
    void diffNamesTheRepairJudgementInChinese() {
        ObjectMapper json = new ObjectMapper();
        NodeSuggestVO fresh = NodeSuggestService.decideBreak(probeRow(),
                readings(probe(T1, BreakDetailVO.OUTCOME_FAILED, "掉出连板名单")), json);
        String seen = fresh.getFingerprint().replace("未续板", "待判定");

        List<String> diffs = NodeSuggestService.diff(json, seen, fresh);

        assertEquals(1, diffs.size(), String.valueOf(diffs));
        assertTrue(diffs.get(0).contains("续板判定：待判定 → 现在算出 未续板"), diffs.toString());
    }

    /** 高低切的九键 diff 一条都不该看见"续板判定"——两张键表各比各的格。 */
    @Test
    void splitDiffNeverMentionsRepair() {
        ObjectMapper json = new ObjectMapper();
        NodeSuggestVO fresh = new NodeSuggestVO();
        fresh.setNodeType(NodeSuggestService.TYPE_FILL_SAME);
        fresh.setNodeTypeLabel("补位节点");
        fresh.setStatus(NodeSuggestService.STATUS_VALID);
        fresh.setRepairStatusLabel("续板成功");

        List<String> diffs = NodeSuggestService.diff(json, "{}", fresh);

        assertEquals(2, diffs.size(), diffs.toString());
        assertFalse(diffs.stream().anyMatch(d -> d.startsWith("续板判定")), diffs.toString());
    }

    // ---------- 落库那一侧 ----------

    /**
     * 采纳破壁行只写自己那几格：老龙反包、晋级数、晋级率是高低切的账。
     *
     * <p>{@code t_node_event} 这三列在破壁行上原本有值（比如他先建了行、后来手工填过），
     * 一次破壁采纳把它们清空同样荒唐，所以这里断言的是"一个字都不碰"。
     */
    @Test
    void adoptWritesRepairAndStatusButNeverSplitColumns() {
        NodeEvent row = probeRow();
        row.setT1PromotionCount(9);
        row.setT1AnchorRepack(1);
        NodeEventMapper mapper = mock(NodeEventMapper.class);
        BreakDetailService detail = mock(BreakDetailService.class);
        when(detail.vo(USER, D0)).thenReturn(probe(T1, BreakDetailVO.OUTCOME_SUCCESS, "8-31 续板到 8 板"));
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(row);
        NodeSuggestService service =
                new NodeSuggestService(mapper, null, null, new ObjectMapper(), null, detail);

        NodeSuggestVO seen = service.suggest(USER, 1L);
        service.adopt(USER, 1L, seen.getFingerprint());

        ArgumentCaptor<NodeEvent> saved = ArgumentCaptor.forClass(NodeEvent.class);
        verify(mapper).updateById(saved.capture());
        assertEquals(BreakDetailVO.OUTCOME_SUCCESS, saved.getValue().getRepairStatus());
        assertEquals(NodeSuggestService.STATUS_VALID, saved.getValue().getStatus());
        assertEquals(Integer.valueOf(9), saved.getValue().getT1PromotionCount(), "高低切那格原样留着");
        assertEquals(Integer.valueOf(1), saved.getValue().getT1AnchorRepack());
        assertEquals("深中华Ａ(" + SHEN + ")", saved.getValue().getNodeStock());
    }

    /** 缺数那次采纳必须被挡下来，而且挡下来的话里说的是"为什么判不了"。 */
    @Test
    void adoptRefusesWhileTheNextDayIsUnsettled() {
        NodeEventMapper mapper = mock(NodeEventMapper.class);
        BreakDetailService detail = mock(BreakDetailService.class);
        when(detail.vo(USER, D0)).thenReturn(probe(null, BreakDetailVO.OUTCOME_PENDING, "次一交易日的明细还没落库"));
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(probeRow());
        NodeSuggestService service = new NodeSuggestService(mapper, null, null,
                new ObjectMapper(), null, detail);
        NodeSuggestVO seen = service.suggest(USER, 1L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.adopt(USER, 1L, seen.getFingerprint()));

        assertTrue(e.getMessage().contains("还不能采纳"), e.getMessage());
        assertTrue(e.getMessage().contains("还没落库"), e.getMessage());
        verify(mapper, org.mockito.Mockito.never()).updateById(any());
    }

    // ---------- fixture ----------

    private static NodeSuggestService.Readings readings(BreakDetailVO d) {
        NodeSuggestService.Readings r = new NodeSuggestService.Readings();
        r.d0 = D0;
        r.breakDetail = d;
        r.t1 = d == null || d.getOutcome() == null ? null : d.getOutcome().getNextDate();
        return r;
    }

    private static NodeEvent probeRow() {
        return row(NodeBreakService.TYPE_PROBE);
    }

    private static NodeEvent breakRow() {
        return row(NodeBreakService.TYPE_BREAK);
    }

    private static NodeEvent row(String nodeType) {
        NodeEvent n = new NodeEvent();
        n.setId(1L);
        n.setUserId(USER);
        n.setD0Date(D0);
        n.setNodeType(nodeType);
        n.setStatus(NodeSuggestService.STATUS_PENDING);
        return n;
    }

    /**
     * 08-28 深中华Ａ 那一天的面板：7 板追平、追的是 6 板线（线已经跟着它抬到 7，
     * 所以这一格读的是进来这天时挂着的那条——写反了就会出现"追 7 板线"这种倒挂）。
     */
    private static BreakDetailVO probe(LocalDate nextDate, String outcome, String reason) {
        BreakDetailVO d = new BreakDetailVO();
        d.setTradeDate(D0);
        d.setEvent(BreakDetailVO.EVENT_PROBE);
        d.setCurvePoint(Boolean.TRUE);
        d.setCeiling(6);
        d.setLineOriginDate(LocalDate.of(2026, 8, 12));
        d.setLineOriginStock(ref("600721", "ST百花", 6));
        d.setSubject(ref(SHEN, "深中华Ａ", 7));
        d.setDetailAvailable(Boolean.TRUE);
        BreakDetailVO.Outcome o = new BreakDetailVO.Outcome();
        o.setResult(outcome);
        o.setNextDate(nextDate);
        o.setNextBoard(BreakDetailVO.OUTCOME_SUCCESS.equals(outcome) ? 8 : 0);
        o.setReason(reason);
        d.setOutcome(o);
        return d;
    }

    private static BreakDetailVO.StockRef ref(String code, String name, Integer board) {
        BreakDetailVO.StockRef r = new BreakDetailVO.StockRef();
        r.setCode(code);
        r.setName(name);
        r.setBoard(board);
        return r;
    }
}
