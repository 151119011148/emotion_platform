package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.emotion.entity.FirstBoardScreen;
import com.emotion.entity.MarketStock;

/**
 * 首板筛选的两条判据：站上年线 + 题材在当日连板天梯里没有。
 *
 * <p>只钉 {@link FirstBoardScreenService#evaluate} 这个纯函数，不连库不联网——
 * 这里最容易写错的三件事都在判据本身：
 * 「判不了」绝不能记成通过（没概念的票、根数不够的年线）；
 * 便宜的题材闸必须先跑并短路掉昂贵的年线（否则一天几十次上游往返白打）；
 * 年线必须含当日收盘、且不足 250 根时不给数而不是按现有根数平均。
 */
class FirstBoardScreenServiceRuleTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 29);

    private static MarketStock first(String code, String close) {
        MarketStock m = new MarketStock();
        m.setTradeDate(D);
        m.setCode(code);
        m.setName("测试票");
        m.setIndustry("汽车配件");
        m.setConsecutive(1);
        m.setClosePrice(close == null ? null : new BigDecimal(close));
        return m;
    }

    /** n 根同一收盘价的升序序列，用于把均值钉成一个可手算的数。 */
    private static List<BigDecimal> flat(int n, String close) {
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new BigDecimal(close));
        }
        return out;
    }

    private static Set<String> ladder(String... concepts) {
        return new HashSet<>(Arrays.asList(concepts));
    }

    /** 题材空白 + 收盘 12 对 249 根 10.00 的年线（10.008）：过。 */
    @Test
    void blankThemeAboveAnnualLinePasses() {
        FirstBoardScreen r = FirstBoardScreenService.evaluate(
                first("000001", "12.000"), Collections.singletonList("机器人概念"),
                ladder("稀土"), flat(249, "10.000"));

        assertTrue(r.getPassed());
        assertEquals(FirstBoardScreen.REASON_PASS, r.getReason());
        assertEquals(new BigDecimal("10.008"), r.getMa250());
        assertEquals(new BigDecimal("19.90"), r.getAbovePct());
        assertEquals(Integer.valueOf(250), r.getBarsUsed());
        assertEquals(Integer.valueOf(0), r.getOverlapCount());
        assertEquals(Integer.valueOf(1), r.getLadderConcepts());
    }

    /** 收盘正好压在线上是「持平」，不算站上——过与不过要有明确一边。 */
    @Test
    void closeEqualToAnnualLineIsNotAbove() {
        FirstBoardScreen r = FirstBoardScreenService.evaluate(
                first("000002", "10.000"), Collections.singletonList("机器人概念"),
                ladder("稀土"), flat(250, "10.000"));

        assertFalse(r.getPassed());
        assertEquals(FirstBoardScreen.REASON_BELOW_MA250, r.getReason());
        assertEquals(new BigDecimal("0.00"), r.getAbovePct());
    }

    /** 题材在天梯里：淘汰，并且<b>不该</b>为它算年线（年线格留空就是没打的证据）。 */
    @Test
    void themePresentInLadderIsCutBeforeAnnualLine() {
        FirstBoardScreen r = FirstBoardScreenService.evaluate(
                first("000003", "12.000"), Arrays.asList("机器人概念", "稀土"),
                ladder("稀土", "固态电池"), null);

        assertFalse(r.getPassed());
        assertEquals(FirstBoardScreen.REASON_THEME_IN_LADDER, r.getReason());
        assertEquals(Integer.valueOf(1), r.getOverlapCount());
        assertNull(r.getMa250());
    }

    /** 一只票挂多个概念时，任一概念被天梯沾上就淘汰。 */
    @Test
    void anyConceptHitCutsIt() {
        FirstBoardScreen r = FirstBoardScreenService.evaluate(
                first("000004", "12.000"), Arrays.asList("机器人概念", "固态电池", "消费电子"),
                ladder("固态电池"), null);

        assertEquals(FirstBoardScreen.REASON_THEME_IN_LADDER, r.getReason());
        assertEquals(Integer.valueOf(1), r.getOverlapCount());
    }

    /** 索引里没有这只票的概念行：判不了，按未通过记，不能当成「题材空白」放它过。 */
    @Test
    void stockWithoutConceptRowsIsNotJudgedAsBlank() {
        FirstBoardScreen r = FirstBoardScreenService.evaluate(
                first("000005", "12.000"), Collections.emptyList(),
                ladder("稀土"), flat(249, "10.000"));

        assertFalse(r.getPassed());
        assertEquals(FirstBoardScreen.REASON_NO_CONCEPT, r.getReason());
        assertEquals(Integer.valueOf(0), r.getConceptCount());
        assertNull(r.getMa250());
    }

    /** 上市不够一年（只有 200 根已发布收盘）：没有年线，也不拿 201 根凑一个假 250 日均线。 */
    @Test
    void tooFewBarsHasNoAnnualLine() {
        FirstBoardScreen r = FirstBoardScreenService.evaluate(
                first("000006", "12.000"), Collections.singletonList("机器人概念"),
                ladder("稀土"), flat(200, "10.000"));

        assertFalse(r.getPassed());
        assertEquals(FirstBoardScreen.REASON_NO_ANNUAL_LINE, r.getReason());
        assertEquals(Integer.valueOf(201), r.getBarsUsed());
        assertNull(r.getMa250());
    }

    /** 日 K 那一趟整个没拉到：与次新同码，但 bars_used=1（只有当日那根）能把两者分开。 */
    @Test
    void missingBarsShowAsOneRootNotAsAFakeLine() {
        FirstBoardScreen r = FirstBoardScreenService.evaluate(
                first("000007", "12.000"), Collections.singletonList("机器人概念"),
                ladder("稀土"), Collections.emptyList());

        assertEquals(FirstBoardScreen.REASON_NO_ANNUAL_LINE, r.getReason());
        assertEquals(Integer.valueOf(1), r.getBarsUsed());
    }

    /** 池内当日收盘为空：年线凑不满 250 根，判不了。 */
    @Test
    void missingCloseCannotBeJudged() {
        FirstBoardScreen r = FirstBoardScreenService.evaluate(
                first("000008", null), Collections.singletonList("机器人概念"),
                ladder("稀土"), flat(249, "10.000"));

        assertFalse(r.getPassed());
        assertEquals(FirstBoardScreen.REASON_NO_CLOSE, r.getReason());
    }

    /** 年线取的是最近 249 根：更早的收盘价不该混进来。 */
    @Test
    void annualLineUsesOnlyTheMostRecent249() {
        List<BigDecimal> prior = flat(300, "1.000");
        for (int i = 51; i < 300; i++) {
            prior.set(i, new BigDecimal("10.000"));   // 最近 249 根都是 10，更早的是 1
        }
        FirstBoardScreen r = FirstBoardScreenService.evaluate(
                first("000009", "12.000"), Collections.singletonList("机器人概念"),
                ladder("稀土"), prior);

        assertEquals(new BigDecimal("10.008"), r.getMa250());
        assertTrue(r.getPassed());
    }

    /** 249 根里只要差一根就是假年线：边界要卡死在「够 249」。 */
    @Test
    void annualLineNeedsExactly249PriorCloses() {
        assertNull(FirstBoardScreenService.annualLine(flat(248, "10.000"), new BigDecimal("12.000")));
        assertEquals(new BigDecimal("10.008"),
                FirstBoardScreenService.annualLine(flat(249, "10.000"), new BigDecimal("12.000")));
    }
}
