package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.entity.DailyRecord;
import com.emotion.entity.MarketDaily;
import com.emotion.entity.NodeEvent;
import com.emotion.vo.BreakDetailVO;
import com.emotion.vo.ShoubanVO;
import com.emotion.vo.TiantiVO;

/**
 * 破壁详情面板的读数（{@link BreakDetailService#compose}）。全内存 fixture，不碰 DB / Spring。
 *
 * <p>三个真实样本按他 2026-09 复盘里核对过的那几天断言：08-28 深中华Ａ（7 板追平、换手 14 次炸板、
 * 助攻 2/1/0、次日掉榜＝没兑现）、09-22 华瓷股份（一字 0.74% 换手缩量、助攻 3/0/1 反而最多）、
 * 07-22→07-23 立新能源（真破壁成功，唯一一次两行都成立的日子）。
 *
 * <p>为什么样本里助攻最多的那一次恰恰是失败的：这就是他把助攻定成"只算、只展示"的理由——
 * n=2 不外推，但闸门一旦吃了它，09-22 那种"梯队最齐却死在缩量秒板上"的日子就会被判成可以开仓。
 * 所以这里同时钉住：<b>闸门警告里一条助攻都没有</b>。
 */
class BreakDetailServiceTest {

    private static final LocalDate D_PROBE = LocalDate.of(2026, 8, 28);
    private static final LocalDate D_NEXT = LocalDate.of(2026, 8, 31);
    private static final String SHEN = "000017";

    /** 08-28 深中华Ａ：试探日读数——追平 7 板线、同属性助攻 2/1/0、炸板 14 次、次日掉榜＝FAILED。 */
    @Test
    void probeDayCarriesAssistBoardAndFailedSettlement() {
        BreakDetailVO vo = BreakDetailService.compose(shenSource());

        assertEquals(BreakDetailVO.EVENT_PROBE, vo.getEvent());
        assertEquals(Boolean.TRUE, vo.getCurvePoint());
        assertEquals(SHEN, vo.getSubject().getCode());
        assertEquals(Integer.valueOf(7), vo.getSubject().getBoard(), "板高读的是它当天在连板名单上的那一格");
        assertEquals(Integer.valueOf(7), vo.getCeiling(), "试探之后周期归它，线跟它抬到 7 板");

        BreakDetailVO.Assist a = vo.getAssist();
        assertEquals(Integer.valueOf(2), a.getSameIndustryFirst());
        assertEquals(Integer.valueOf(1), a.getSameIndustrySecond());
        assertEquals(Integer.valueOf(0), a.getSameIndustryThirdPlus());
        assertEquals(Integer.valueOf(3), a.getTotal());
        assertEquals(Boolean.TRUE, a.getLadderOk());
        assertEquals(4, a.getFirstBoardTotal().intValue(), "广度是当天封住的首板只数，与同属性无关");
        assertEquals(3, a.getFirstBoardIndustries().intValue());
        assertEquals(2, a.getFirstStocks().size(), "助攻名单里不许把主角自己算进去");

        assertEquals("TURNOVER", vo.getBoard().getPattern());
        assertEquals(Integer.valueOf(14), vo.getBoard().getBreakCount());
        assertNull(vo.getBoard().getOneWordKilling(), "放量换手的板，不是缩量秒板");

        assertEquals(BreakDetailVO.OUTCOME_FAILED, vo.getOutcome().getResult());
        assertEquals(D_NEXT, vo.getOutcome().getNextDate());
        assertEquals(Integer.valueOf(0), vo.getOutcome().getNextBoard());
        assertTrue(vo.getOutcome().getReason().contains("掉出连板名单"), vo.getOutcome().getReason());
        assertNull(vo.getGateWarnings(), "炸板率 13.5%、昨溢价 +2.81%、阶段发酵：三条闸门都没碰线");
    }

    /**
     * 09-22 华瓷股份：助攻最多（3/0/1）的一次，盘的却是 0.74% 换手的一字秒板。
     *
     * <p>这条钉的是"助攻不许当闸门"这条口径的来由——它要是进了 ready，这次最齐的梯队会被判成可开仓。
     */
    @Test
    void richestLadderStillSettlesFailedAndGateStaysQuietAboutAssist() {
        BreakDetailService.Source s = new BreakDetailService.Source();
        s.date = LocalDate.of(2026, 9, 22);
        TiantiVO.HeightPoint day = pt(s.date, 6, lad(6, "002760", "华瓷股份"), lad(5, "600999", "别的票"));
        day.setCeiling(6);
        day.setIsProbe(Boolean.TRUE);
        day.setProbeStock(new TiantiVO.HeightStock("002760", "华瓷股份"));
        day.setLineStock(new TiantiVO.HeightStock("000993", "闽东电力"));
        day.setLineOriginDate(LocalDate.of(2026, 9, 16));
        day.setLineOriginStock(new TiantiVO.HeightStock("000993", "闽东电力"));
        s.point = day;
        TiantiVO.HeightPoint next = pt(s.date.plusDays(3), 6, lad(6, "002760", "华瓷股份"));
        next.setCeiling(6);
        s.nextPoint = next;   // 还在榜、板高没加：滞涨，同样算没兑现
        s.subjectCode = "002760";
        s.ladderRows = Arrays.asList(
                row("002760", "华瓷股份", "家居用品", 6, pct("10.02")),
                row("600999", "别的票", "家居用品", 5, pct("10.01")));
        s.subjectRow = s.ladderRows.get(0);
        s.subjectRow.setPattern("ONE_LINE");
        s.subjectRow.setTurnoverRate(new BigDecimal("0.74"));
        s.subjectRow.setSealRatio(new BigDecimal("5.35"));
        s.subjectRow.setOneWordKilling(Boolean.TRUE);
        s.sealed = Arrays.asList(
                sb("001111", "甲", "家居用品"), sb("002222", "乙", "家居用品"),
                sb("603333", "丙", "家居用品"), sb("004444", "丁", "造纸"));
        s.detailAvailable = true;
        s.day = marketDaily("29.10", "2.57");
        s.record = record("发酵");

        BreakDetailVO vo = BreakDetailService.compose(s);

        BreakDetailVO.Assist a = vo.getAssist();
        assertEquals(Integer.valueOf(3), a.getSameIndustryFirst());
        assertEquals(Integer.valueOf(0), a.getSameIndustrySecond());
        assertEquals(Integer.valueOf(1), a.getSameIndustryThirdPlus());
        assertEquals(Boolean.TRUE, a.getLadderOk());
        assertEquals("ONE_LINE", vo.getBoard().getPattern());
        assertEquals(new BigDecimal("0.74"), vo.getBoard().getTurnoverRate());
        assertEquals(Boolean.TRUE, vo.getBoard().getOneWordKilling());
        assertEquals(BreakDetailVO.OUTCOME_FAILED, vo.getOutcome().getResult());
        assertTrue(vo.getOutcome().getReason().contains("滞涨"), vo.getOutcome().getReason());
        assertNull(vo.getGateWarnings(), "29.1% < 50%、溢价 +2.57%：闸门不该出声，更不该提助攻");
    }

    /**
     * 2026-08-03 之前的名义天梯：板上名单有、逐只明细没有。
     *
     * <p>首板助攻必须留 <b>null</b> 而不是 0——{@code consecutive} 在名义行上兜成 1，
     * 兜出来的"0 只首板"会把"没数据"伪造进一段根本没法验证的历史里。二板/三板以上数得出来，照数。
     */
    @Test
    void nominalLadderLeavesFirstBoardAssistUnknownInsteadOfZero() {
        BreakDetailService.Source s = new BreakDetailService.Source();
        s.date = LocalDate.of(2026, 7, 6);
        TiantiVO.HeightPoint day = pt(s.date, 4, lad(4, "603137", "恒尚节能"));
        day.setCeiling(4);
        day.setIsProbe(Boolean.TRUE);
        day.setProbeStock(new TiantiVO.HeightStock("603137", "恒尚节能"));
        s.point = day;
        s.subjectCode = "603137";
        s.ladderRows = Arrays.asList(row("603137", "恒尚节能", "装修装饰", 4, null));
        s.subjectRow = s.ladderRows.get(0);
        s.sealed = new ArrayList<>();
        s.detailAvailable = false;

        BreakDetailVO vo = BreakDetailService.compose(s);

        assertEquals(Boolean.FALSE, vo.getDetailAvailable());
        assertTrue(vo.getDetailMissingReason().contains("名义天梯"), vo.getDetailMissingReason());
        BreakDetailVO.Assist a = vo.getAssist();
        assertNull(a.getSameIndustryFirst(), "首板数判不了＝不知道，不是 0 只");
        assertNull(a.getTotal());
        assertNull(a.getLadderOk());
        assertEquals(Integer.valueOf(0), a.getSameIndustrySecond(), "板上名单里有行业有板高，这两项数得出来");
        assertNull(vo.getBoard(), "盘口整块留空，不兜一个看起来正常的形态");
    }

    /** 曲线上压根没有这一天（那天没有任何涨停明细）：是判不起来，不是"没有破壁"。 */
    @Test
    void missingCurvePointSaysItCannotJudge() {
        BreakDetailService.Source s = new BreakDetailService.Source();
        s.date = LocalDate.of(2026, 1, 1);

        BreakDetailVO vo = BreakDetailService.compose(s);

        assertEquals(BreakDetailVO.EVENT_NONE, vo.getEvent());
        assertEquals(Boolean.FALSE, vo.getCurvePoint());
        assertTrue(vo.getDetailMissingReason().contains("没有点"), vo.getDetailMissingReason());
        assertNull(vo.getOutcome());
    }

    /**
     * 07-23 立新能源：真破壁成功的日子——标签上的"破壁 5→6"记的是<b>它追的那条线</b>，
     * 不是结算日已经抬高的线（写高了就会出现"破壁 8→6"那种倒挂）。
     *
     * <p>成功日不再算自己的次日结算：那天已经是结论本身，它的次日是新一轮周期的事。
     */
    @Test
    void breakDayReportsChasedLineAndNoOwnSettlement() {
        BreakDetailService.Source s = new BreakDetailService.Source();
        s.date = LocalDate.of(2026, 7, 23);
        TiantiVO.HeightPoint prev = pt(LocalDate.of(2026, 7, 22), 5, lad(5, "001258", "立新能源"));
        prev.setCeiling(5);
        prev.setIsProbe(Boolean.TRUE);
        prev.setProbeStock(new TiantiVO.HeightStock("001258", "立新能源"));
        TiantiVO.HeightPoint day = pt(s.date, 6, lad(6, "001258", "立新能源"));
        day.setCeiling(6);
        day.setIsBreak(Boolean.TRUE);
        day.setPrevHigh(5);
        day.setBreakStock(new TiantiVO.HeightStock("001258", "立新能源"));
        day.setLineStock(new TiantiVO.HeightStock("001258", "立新能源"));
        s.point = day;
        s.prevPoint = prev;
        s.nextPoint = pt(LocalDate.of(2026, 7, 24), 6, lad(6, "001258", "立新能源"));
        s.subjectCode = "001258";
        s.ladderRows = Arrays.asList(row("001258", "立新能源", "电力", 6, pct("10.01")));
        s.subjectRow = s.ladderRows.get(0);
        s.sealed = new ArrayList<>();
        s.detailAvailable = true;

        BreakDetailVO vo = BreakDetailService.compose(s);

        assertEquals(BreakDetailVO.EVENT_BREAK, vo.getEvent());
        assertEquals(Integer.valueOf(5), vo.getPrevHigh());
        assertEquals(Integer.valueOf(6), vo.getSubject().getBoard());
        assertEquals(LocalDate.of(2026, 7, 22), vo.getProbeDate());
        assertNull(vo.getOutcome(), "成功日不再判自己的次日");
    }

    /** 试探日往前找那次试探：前一个点不是同一只票的 ☆ 就不认，免得把别人的周期挂到这一行上。 */
    @Test
    void probeDateOnlyWhenPreviousDayIsTheSameStocksProbe() {
        BreakDetailService.Source s = new BreakDetailService.Source();
        s.date = LocalDate.of(2026, 7, 23);
        TiantiVO.HeightPoint prev = pt(LocalDate.of(2026, 7, 22), 5, lad(5, "600664", "哈药股份"));
        prev.setIsProbe(Boolean.TRUE);
        prev.setProbeStock(new TiantiVO.HeightStock("600664", "哈药股份"));
        TiantiVO.HeightPoint day = pt(s.date, 6, lad(6, "001258", "立新能源"));
        day.setIsBreak(Boolean.TRUE);
        day.setPrevHigh(5);
        day.setBreakStock(new TiantiVO.HeightStock("001258", "立新能源"));
        s.point = day;
        s.prevPoint = prev;
        s.subjectCode = "001258";

        BreakDetailVO vo = BreakDetailService.compose(s);

        assertNull(vo.getProbeDate(), "前一天追平的是别人，不是这次破壁的那一次试探");
    }

    /** 闸门只认他写了数的三条：炸板率 >50%、昨溢价 <0、阶段含"退潮"。助攻与盘口一条都不许进。 */
    @Test
    void gateSpeaksOnlyOnHisThreeNumbers() {
        BreakDetailService.Source s = shenSource();
        s.day = marketDaily("60.00", "-1.20");
        s.record = record("退潮(强制)");

        List<String> warnings = BreakDetailService.gateWarnings(s);

        assertEquals(3, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("炸板率 60%"), warnings.toString());
        assertTrue(warnings.get(1).contains("昨日涨停溢价 -1.2%"), warnings.toString());
        assertTrue(warnings.get(2).contains("退潮(强制)"), warnings.toString());
    }

    /** 等于 50%、等于 0 都不算过线：他那两条写的是"＞50%""＜0"，边界不自己收紧。 */
    @Test
    void gateBoundariesAreNotInclusive() {
        BreakDetailService.Source s = shenSource();
        s.day = marketDaily("50.00", "0.00");
        s.record = record("发酵");

        assertNull(BreakDetailService.gateWarnings(s));
    }

    /** 这只票谁都不挂在：立完就按新权重计分，说清是多少。 */
    @Test
    void scoreImpactWhenStockIsNotANodeYet() {
        String note = BreakDetailService.scoreImpactNote(new ArrayList<NodeEvent>(), D_PROBE, SHEN,
                NodeBreakService.TYPE_PROBE);

        assertTrue(note.contains("现在不是任何在册节点"), note);
        assertTrue(note.contains("0.6"), note);
    }

    /**
     * 撞车的那一格：深中华Ａ 现在是 9-18 那条节点的节点票（{@code node_type} 为 NULL → 节点分 1.0）。
     * 给 08-28 立试探行之后，08-28 往后窗口里的候选日它的节点分会从 1.0 掉到 0.6——
     * 这条必须在<b>点"立为节点"之前</b>就摊开，不能写完再回头说。
     */
    @Test
    void scoreImpactSaysWhatStaysPutWhenThisLineIsEarlier() {
        NodeEvent held = new NodeEvent();
        held.setD0Date(LocalDate.of(2026, 9, 18));
        held.setNodeStock("深中华Ａ(" + SHEN + ")");

        String note = BreakDetailService.scoreImpactNote(Arrays.asList(held), D_PROBE, SHEN,
                NodeBreakService.TYPE_PROBE);

        assertTrue(note.contains("未识别类型"), note);
        assertTrue(note.contains("节点分 1"), note);
        assertTrue(note.contains("排在它后面，不改变现在的分"), note);
    }

    /** 反过来：库里那条更早，这次立的更晚，就是顶掉它。 */
    @Test
    void scoreImpactSaysItTakesOverWhenNewer() {
        NodeEvent held = new NodeEvent();
        held.setD0Date(LocalDate.of(2026, 8, 20));
        held.setNodeStock("深中华Ａ(" + SHEN + ")");
        held.setNodeType(NodeBreakService.TYPE_PROBE);

        String note = BreakDetailService.scoreImpactNote(Arrays.asList(held), D_PROBE, SHEN,
                NodeBreakService.TYPE_BREAK);

        assertTrue(note.contains("试探破壁 · 观察"), note);
        assertTrue(note.contains("会顶掉它"), note);
    }

    /** 别的票的行不参与：同股只比同一只代码。 */
    @Test
    void scoreImpactIgnoresOtherStocks() {
        NodeEvent other = new NodeEvent();
        other.setD0Date(LocalDate.of(2026, 8, 20));
        other.setNodeStock("华瓷股份(002760)");

        assertTrue(BreakDetailService.scoreImpactNote(Arrays.asList(other), D_PROBE, SHEN,
                NodeBreakService.TYPE_PROBE).contains("现在不是任何在册节点"));
    }

    // ---------- fixture ----------

    /** 08-28 深中华Ａ 那一天：曲线上 7 板 ☆，次日 8-31 掉榜。 */
    private static BreakDetailService.Source shenSource() {
        BreakDetailService.Source s = new BreakDetailService.Source();
        s.date = D_PROBE;
        TiantiVO.HeightPoint day = pt(D_PROBE, 7, lad(7, SHEN, "深中华Ａ"), lad(5, "600811", "东方集团"));
        day.setCeiling(7);
        day.setIsProbe(Boolean.TRUE);
        day.setProbeStock(new TiantiVO.HeightStock(SHEN, "深中华Ａ"));
        day.setLineStock(new TiantiVO.HeightStock(SHEN, "深中华Ａ"));
        day.setLineOriginDate(LocalDate.of(2026, 8, 12));
        day.setLineOriginStock(new TiantiVO.HeightStock("600721", "ST百花"));
        s.point = day;
        s.prevPoint = pt(LocalDate.of(2026, 8, 27), 6, lad(6, "600721", "ST百花"));
        TiantiVO.HeightPoint next = pt(D_NEXT, 7, lad(7, "600811", "东方集团"));
        next.setCeiling(7);
        s.nextPoint = next;
        s.subjectCode = SHEN;
        TiantiVO.Row subject = row(SHEN, "深中华Ａ", "黄金", 7, pct("10.02"));
        subject.setPattern("TURNOVER");
        subject.setBreakCount(14);
        subject.setTurnoverRate(pct("18.30"));
        subject.setFirstSealTime(103500);
        s.ladderRows = Arrays.asList(subject,
                row("600811", "东方集团", "农业综合", 5, pct("10.00")),
                row("000506", "中润资源", "黄金", 2, pct("10.05")));
        s.subjectRow = subject;
        s.sealed = Arrays.asList(
                sb("002856", "美芝股份", "黄金"), sb("605388", "均瑶健康", "黄金"),
                sb("000981", "湘财股份", "证券"), sb("600227", "赤天化", "化肥"));
        s.detailAvailable = true;
        s.day = marketDaily("13.50", "2.81");
        s.record = record("发酵");
        return s;
    }

    private static TiantiVO.HeightPoint pt(LocalDate date, int h, TiantiVO.LadderStock... ladder) {
        TiantiVO.HeightPoint p = new TiantiVO.HeightPoint();
        p.setTradeDate(date);
        p.setMaxHeight(h);
        List<TiantiVO.LadderStock> ls = new ArrayList<>(Arrays.asList(ladder));
        p.setLadder(ls);
        List<TiantiVO.HeightStock> tops = new ArrayList<>();
        for (TiantiVO.LadderStock each : ls) {
            if (each.getBoard() != null && each.getBoard() == h) {
                tops.add(new TiantiVO.HeightStock(each.getCode(), each.getName()));
            }
        }
        p.setStocks(tops);
        p.setStockCount(tops.size());
        return p;
    }

    private static TiantiVO.LadderStock lad(int board, String code, String name) {
        return new TiantiVO.LadderStock(board, code, name);
    }

    private static TiantiVO.Row row(String code, String name, String industry, int board, BigDecimal changePct) {
        TiantiVO.Row r = new TiantiVO.Row();
        r.setCode(code);
        r.setName(name);
        r.setIndustry(industry);
        r.setBoard(board);
        r.setChangePct(changePct);
        return r;
    }

    private static ShoubanVO.Row sb(String code, String name, String industry) {
        ShoubanVO.Row r = new ShoubanVO.Row();
        r.setCode(code);
        r.setName(name);
        r.setIndustry(industry);
        r.setChangePct(pct("10.00"));
        r.setPattern("TURNOVER");
        return r;
    }

    private static MarketDaily marketDaily(String bombRate, String premium) {
        MarketDaily d = new MarketDaily();
        d.setBrokenBoardRate(new BigDecimal(bombRate));
        d.setYesterdayLimitPremium(new BigDecimal(premium));
        d.setLimitUpCount(78);
        return d;
    }

    private static DailyRecord record(String stage) {
        DailyRecord r = new DailyRecord();
        r.setStage(stage);
        return r;
    }

    private static BigDecimal pct(String value) {
        return new BigDecimal(value);
    }
}
