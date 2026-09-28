package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.NodeEvent;
import com.emotion.mapper.NodeEventMapper;
import com.emotion.vo.BreakNodeCreateVO;
import com.emotion.vo.NodeVO;
import com.emotion.vo.TiantiVO;

/**
 * 把曲线上那颗 ☆/★ 立成节点行（{@link NodeBreakService}）：一次点击落几行、每行写哪几格、
 * 重复点会不会插重。
 *
 * <p>判定不在这里——哪天是试探、哪天算成功读的是 {@link TiantiService}，本类只管翻译。
 * 所以断言集中在三件容易写歪的事上：
 * <ol>
 *   <li><b>{@code break_board} 记的是它追的那条线，不是它自己的板高</b>。试探日当天线已经跟着它抬上去了
 *       （8.28 深中华Ａ 追 6 板线、当天线变 7），拿当天的 ceiling 落库就是"追 7 板线"的倒挂——
 *       这也是 {@link TiantiService#chasedLine} 存在的唯一理由；</li>
 *   <li>成功日两行、试探日一行，两行的主角都是<b>追线的那只</b>，不是挂线的老龙；</li>
 *   <li>幂等按 {@code user + d0Date + nodeType} 逐行挡：{@code t_node_event} 上没有唯一键，
 *       服务里不挡就是点一次多一行。</li>
 * </ol>
 */
class NodeBreakServiceTest {

    private static final Long USER = 1L;
    private static final LocalDate D_0722 = LocalDate.of(2026, 7, 22);
    private static final LocalDate D_0723 = LocalDate.of(2026, 7, 23);
    private static final LocalDate D_0827 = LocalDate.of(2026, 8, 27);
    private static final LocalDate D_0828 = LocalDate.of(2026, 8, 28);
    private static final String LI = "001258";
    private static final String SHEN = "000017";

    /** 试探日只有一行：追的是<b>进来这天时挂着的那条线</b>，不是它自己抬上去的那条。 */
    @Test
    void loneProbeChasesTheLineThatWasUpBeforeItCaughtUp() {
        TiantiVO.HeightPoint prev = point(D_0827, 6, 6, lad(6, "600721", "ST百花"), lad(5, SHEN, "深中华Ａ"));
        TiantiVO.HeightPoint day = point(D_0828, 7, 7, lad(7, SHEN, "深中华Ａ"), lad(5, "600811", "东方集团"));
        day.setIsProbe(Boolean.TRUE);
        day.setProbeStock(stock(SHEN, "深中华Ａ"));

        List<NodeBreakService.Line> lines = NodeBreakService.linesOf(day, prev);

        assertEquals(1, lines.size());
        NodeBreakService.Line line = lines.get(0);
        assertEquals(NodeBreakService.TYPE_PROBE, line.nodeType);
        assertEquals(D_0828, line.d0Date);
        assertEquals(SHEN, line.stockCode);
        assertEquals(Integer.valueOf(7), line.stockBoard, "它当天实际打到的板高");
        assertEquals(Integer.valueOf(6), line.chasedLine, "落库的 break_board 是它追的那条线：6，不是抬过之后的 7");
        assertEquals(NodeBreakService.REPAIR_PENDING, line.repairStatus);
    }

    /**
     * 成功日两行：这一行是「破壁成功 · 出手」，同时把前一天那次试探补齐——
     * 否则成功行孤零零挂在表里，前面那次追平查无实据。
     */
    @Test
    void successDayBackfillsTheProbeRowItCameFrom() {
        TiantiVO.HeightPoint prev = point(D_0722, 5, 5, lad(5, LI, "立新能源"));
        prev.setIsProbe(Boolean.TRUE);
        prev.setProbeStock(stock(LI, "立新能源"));
        TiantiVO.HeightPoint day = point(D_0723, 6, 6, lad(6, LI, "立新能源"));
        day.setIsBreak(Boolean.TRUE);
        day.setPrevHigh(5);
        day.setBreakStock(stock(LI, "立新能源"));

        List<NodeBreakService.Line> lines = NodeBreakService.linesOf(day, prev);

        assertEquals(2, lines.size());
        assertEquals(NodeBreakService.TYPE_BREAK, lines.get(0).nodeType);
        assertEquals(D_0723, lines.get(0).d0Date);
        assertEquals(Integer.valueOf(5), lines.get(0).chasedLine);
        assertNull(lines.get(0).repairStatus, "成功行没有「次日续没续板」这一项，它本身就是结论");
        assertEquals(NodeBreakService.TYPE_PROBE, lines.get(1).nodeType);
        assertEquals(D_0722, lines.get(1).d0Date);
        assertEquals(Integer.valueOf(5), lines.get(1).stockBoard);
        assertEquals(Integer.valueOf(5), lines.get(1).chasedLine);
        assertEquals(NodeBreakService.REPAIR_PENDING, lines.get(1).repairStatus);
    }

    /** 前一天追平的是<b>别的票</b>：那是别人的周期，不许挂到这次破壁头上补一行。 */
    @Test
    void successDayDoesNotBackfillSomeoneElsesProbe() {
        TiantiVO.HeightPoint prev = point(D_0722, 5, 5, lad(5, "600664", "哈药股份"));
        prev.setIsProbe(Boolean.TRUE);
        prev.setProbeStock(stock("600664", "哈药股份"));
        TiantiVO.HeightPoint day = point(D_0723, 6, 6, lad(6, LI, "立新能源"));
        day.setIsBreak(Boolean.TRUE);
        day.setPrevHigh(5);
        day.setBreakStock(stock(LI, "立新能源"));

        List<NodeBreakService.Line> lines = NodeBreakService.linesOf(day, prev);

        assertEquals(1, lines.size());
        assertEquals(NodeBreakService.TYPE_BREAK, lines.get(0).nodeType);
    }

    /** 既没有 ☆ 也没有 ★ 的一天没有节点可立；曲线上没有点同样算"没有"。 */
    @Test
    void nothingLandsWithoutAnEvent() {
        TiantiVO.HeightPoint plain = point(D_0828, 5, 5, lad(5, "600811", "东方集团"));
        assertTrue(NodeBreakService.linesOf(plain, null).isEmpty());
        assertTrue(NodeBreakService.linesOf(null, null).isEmpty());
    }

    /**
     * 落库那几格：{@code node_stock} 用「名称(代码)」与高低切同一写法（{@link NodeService#stockCodeOf} 要能解析），
     * {@code system_type} 留空串——破壁节点既不是系统A 也不是系统B，硬套一个标签等于凭空造分类。
     */
    @Test
    void entityCarriesChasedLineAndLeavesSplitColumnsAlone() {
        NodeEvent e = NodeBreakService.toEntity(new NodeBreakService.Line(D_0828, NodeBreakService.TYPE_PROBE,
                stock(SHEN, "深中华Ａ"), 7, 6, NodeBreakService.REPAIR_PENDING));

        assertEquals(D_0828, e.getD0Date());
        assertEquals(NodeBreakService.TYPE_PROBE, e.getNodeType());
        assertEquals("深中华Ａ(" + SHEN + ")", e.getNodeStock());
        assertEquals(SHEN, e.getBreakStockCode());
        assertEquals(Integer.valueOf(7), e.getNodeStockMaxBoard());
        assertEquals(Integer.valueOf(6), e.getBreakBoard());
        assertEquals(NodeBreakService.REPAIR_PENDING, e.getRepairStatus());
        assertEquals("待验证", e.getStatus());
        assertEquals("", e.getSystemType());
        assertNull(e.getT1PromotionCount(), "晋级数/晋级率/反包是高低切的账，破壁行一个都不该写");
        assertEquals(SHEN, NodeService.stockCodeOf(e.getNodeStock()));
    }

    /** 类型闭集：破壁两个码收，拼错的必须挡下来——放它进去会被节点分按 0 分算，一个字母就是一次无声降权。 */
    @Test
    void typeSetAcceptsBreakCodesAndRejectsTypos() {
        NodeService.checkNodeType(NodeBreakService.TYPE_PROBE);
        NodeService.checkNodeType(NodeBreakService.TYPE_BREAK);
        assertThrows(IllegalArgumentException.class, () -> NodeService.checkNodeType("SPACE_BREAKST"));
        assertThrows(IllegalArgumentException.class, () -> NodeService.checkNodeType("破壁成功"));
    }

    /** 事件码 → 类型码：NONE 没有类型，别给"这天什么都没发生"立一行。 */
    @Test
    void eventTypeMapsOnlyForRealEvents() {
        assertEquals(NodeBreakService.TYPE_PROBE, NodeBreakService.typeOfEvent(com.emotion.vo.BreakDetailVO.EVENT_PROBE));
        assertEquals(NodeBreakService.TYPE_BREAK,
                NodeBreakService.typeOfEvent(com.emotion.vo.BreakDetailVO.EVENT_BREAK));
        assertNull(NodeBreakService.typeOfEvent(com.emotion.vo.BreakDetailVO.EVENT_NONE));
    }

    /** 点一次成功日：两行一起插，回执把改分那句带回来。 */
    @Test
    void createOnSuccessDayInsertsBothRowsAtOnce() {
        Fixture f = fixture(Arrays.asList(probePoint(), breakPoint()));
        when(f.nodes.create(eq(USER), any(NodeEvent.class))).thenReturn(new NodeVO());

        BreakNodeCreateVO out = f.service.createNodes(USER, D_0723);

        ArgumentCaptor<NodeEvent> created = ArgumentCaptor.forClass(NodeEvent.class);
        verify(f.nodes, times(2)).create(eq(USER), created.capture());
        assertEquals(Arrays.asList(NodeBreakService.TYPE_BREAK, NodeBreakService.TYPE_PROBE),
                Arrays.asList(created.getAllValues().get(0).getNodeType(),
                        created.getAllValues().get(1).getNodeType()));
        assertEquals(D_0723, created.getAllValues().get(0).getD0Date());
        assertEquals(D_0722, created.getAllValues().get(1).getD0Date());
        assertEquals(Boolean.FALSE, out.getAlreadyExists());
        assertEquals(2, out.getRows().size());
        verify(f.detail).scoreImpact(eq(USER), any(), any(), any());
    }

    /**
     * 重复点「立为节点」不许插重：同 {@code user + d0Date + nodeType} 有行就把既有行交回去。
     *
     * <p>{@code t_node_event} 上只有三个普通索引、没有唯一键（V1:225-257），数据库不会替我们挡，
     * 所以这一条只能钉在服务里——不然曲线上的星号点几下，节点表就多几行同一天同类型的重复账。
     */
    @Test
    void secondClickReturnsTheExistingRowInsteadOfInsertingAgain() {
        Fixture f = fixture(Arrays.asList(probePoint(), breakPoint()));
        when(f.mapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(held(77L), held(78L));
        when(f.nodes.detail(USER, 77L)).thenReturn(row(77L));
        when(f.nodes.detail(USER, 78L)).thenReturn(row(78L));

        BreakNodeCreateVO out = f.service.createNodes(USER, D_0723);

        verify(f.nodes, never()).create(any(), any());
        assertEquals(Boolean.TRUE, out.getAlreadyExists());
        assertEquals(Arrays.asList(77L, 78L),
                Arrays.asList(out.getRows().get(0).getId(), out.getRows().get(1).getId()));
    }

    private static NodeEvent held(long id) {
        NodeEvent e = new NodeEvent();
        e.setId(id);
        return e;
    }

    private static NodeVO row(long id) {
        NodeVO vo = new NodeVO();
        vo.setId(id);
        return vo;
    }

    /** 一半立过一半没立：只插缺的那一行，回执仍算"新建过"，不能报成"早就立过了"。 */
    @Test
    void partiallyExistingBatchStillInsertsTheMissingRow() {
        Fixture f = fixture(Arrays.asList(probePoint(), breakPoint()));
        NodeEvent held = new NodeEvent();
        held.setId(88L);
        when(f.mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(held, (NodeEvent) null);
        when(f.nodes.create(eq(USER), any(NodeEvent.class))).thenReturn(new NodeVO());
        when(f.nodes.detail(eq(USER), eq(88L))).thenReturn(new NodeVO());

        BreakNodeCreateVO out = f.service.createNodes(USER, D_0723);

        verify(f.nodes, times(1)).create(eq(USER), any(NodeEvent.class));
        assertEquals(Boolean.FALSE, out.getAlreadyExists());
        assertEquals(2, out.getRows().size());
    }

    /** 这天曲线上没有事件：说的是"没有可立的东西"，不是立了一条空行。 */
    @Test
    void createWithoutEventRefuses() {
        Fixture f = fixture(Arrays.asList(plainPoint(), plainPoint(D_0723)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> f.service.createNodes(USER, D_0723));

        assertTrue(e.getMessage().contains("没有破壁事件"), e.getMessage());
        verify(f.nodes, never()).create(any(), any());
    }

    /** 曲线上没有这一天：与"有那天但那天天平无奇"是同一种没有节点可立。 */
    @Test
    void createOnADayWithoutCurvePointRefuses() {
        Fixture f = fixture(Arrays.asList(plainPoint(), plainPoint(D_0723)));

        assertThrows(IllegalArgumentException.class, () -> f.service.createNodes(USER, LocalDate.of(2026, 7, 24)));
    }

    // ---------- fixture ----------

    private static Fixture fixture(List<TiantiVO.HeightPoint> window) {
        return new Fixture(window);
    }

    /** 只 mock 到"窗口给哪几天"这一层：BreakDay 是 final 类，直接用它包内的构造函数搓。 */
    private static final class Fixture {
        final NodeBreakService service;
        final NodeService nodes;
        final NodeEventMapper mapper;
        final BreakDetailService detail;

        Fixture(List<TiantiVO.HeightPoint> window) {
            TiantiService tianti = mock(TiantiService.class);
            when(tianti.breakDay(any())).thenAnswer(call -> {
                LocalDate date = call.getArgument(0);
                List<TiantiVO.HeightPoint> pts = new ArrayList<>(window);
                return new TiantiService.BreakDay(pts, date);
            });
            this.nodes = mock(NodeService.class);
            this.mapper = mock(NodeEventMapper.class);
            this.detail = mock(BreakDetailService.class);
            when(this.detail.scoreImpact(any(), any(), any(), any())).thenReturn("节点分说明");
            this.service = new NodeBreakService(tianti, nodes, mapper, detail);
        }
    }

    /** 7-22 立新能源 5 板追平 5 板线。 */
    private static TiantiVO.HeightPoint probePoint() {
        TiantiVO.HeightPoint p = point(D_0722, 5, 5, lad(5, LI, "立新能源"));
        p.setIsProbe(Boolean.TRUE);
        p.setProbeStock(stock(LI, "立新能源"));
        return p;
    }

    /** 7-23 它续到 6 板＝破壁成功，追的那条线是 5。 */
    private static TiantiVO.HeightPoint breakPoint() {
        TiantiVO.HeightPoint p = point(D_0723, 6, 6, lad(6, LI, "立新能源"));
        p.setIsBreak(Boolean.TRUE);
        p.setPrevHigh(5);
        p.setBreakStock(stock(LI, "立新能源"));
        return p;
    }

    private static TiantiVO.HeightPoint plainPoint() {
        return plainPoint(D_0722);
    }

    private static TiantiVO.HeightPoint plainPoint(LocalDate date) {
        return point(date, 3, 3, lad(3, "600999", "平无奇的票"));
    }

    private static TiantiVO.HeightPoint point(LocalDate date, int height, Integer ceiling,
                                              TiantiVO.LadderStock... ladder) {
        TiantiVO.HeightPoint p = new TiantiVO.HeightPoint();
        p.setTradeDate(date);
        p.setMaxHeight(height);
        p.setCeiling(ceiling);
        List<TiantiVO.LadderStock> ls = new ArrayList<>(Arrays.asList(ladder));
        p.setLadder(ls);
        List<TiantiVO.HeightStock> tops = new ArrayList<>();
        for (TiantiVO.LadderStock each : ls) {
            if (each.getBoard() != null && each.getBoard() == height) {
                tops.add(stock(each.getCode(), each.getName()));
            }
        }
        p.setStocks(tops);
        p.setStockCount(tops.size());
        return p;
    }

    private static TiantiVO.LadderStock lad(int board, String code, String name) {
        return new TiantiVO.LadderStock(board, code, name);
    }

    private static TiantiVO.HeightStock stock(String code, String name) {
        return new TiantiVO.HeightStock(code, name);
    }
}
