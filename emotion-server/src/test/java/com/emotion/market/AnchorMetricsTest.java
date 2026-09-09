package com.emotion.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.market.TencentClient.DayBar;
import com.emotion.util.TemperatureCalculator;

/**
 * 阵眼跨度的判据。主力用例喂的是 600664 哈药股份 08-12~09-04 的<b>真实前复权日 K 存档</b>，
 * 因为这一维的全部风险都在"复权价做不了绝对价比较"和"触板没触板"这两个细节上，
 * 手造的漂亮数据测不出来的恰恰是它们。
 *
 * <p>档位是 2026-09-09 之后的那一套（{@code ≥9.5%=3 / 收红=2 / 平盘与收绿=0 / 断板=-1 /
 * ≤-5%=-2 / 跌停·盘中触板=-3}），权威实现是 {@code TemperatureCalculator#calcAnchorScore}。
 */
class AnchorMetricsTest {

    private static final LocalDate START = LocalDate.of(2026, 8, 12);

    private final TencentClient client = new TencentClient(null, "http://qt.gtimg.cn", 50,
            "sh000001", "sz399001",
            "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get", 12,
            TencentClient.DEFAULT_INDEX_CLOSE_CODES);

    /** 真实存档按线上路径解析：涨幅由前一根收盘推出，第一根没有前收。 */
    private List<DayBar> haiyao() {
        return client.parseDailyBars(Fixtures.text("kline_daily_600664.json"), "sh600664",
                START, LocalDate.of(2026, 9, 4));
    }

    private static DayBar bar(String date, String close, String pct, String lowPct) {
        DayBar bar = new DayBar();
        bar.setDate(LocalDate.parse(date));
        bar.setClose(new BigDecimal(close));
        bar.setPct(pct == null ? null : new BigDecimal(pct));
        bar.setLowPct(lowPct == null ? null : new BigDecimal(lowPct));
        return bar;
    }

    /** 09-03：收 -7.47% 但最低 -9.96%，盘中确实触过 -9.8% 那条线。这就是"退潮一阶段"的依据。 */
    @Test
    void marksIntradayTouchOnTheRealSeries() {
        AnchorMetrics.Span span = AnchorMetrics.measure(haiyao(), START, null,
                LocalDate.of(2026, 9, 3), AnchorMetrics.LIMIT_MAIN);

        assertTrue(span.isAvailable());
        assertEquals(0, new BigDecimal("-7.47").compareTo(span.getPct()));
        assertEquals(0, new BigDecimal("-9.96").compareTo(span.getLowPct()));
        assertTrue(span.isTouchedLimitDown());
        assertFalse(span.isCloseLimitDown());
        assertEquals(Integer.valueOf(-3), TemperatureCalculator.calcAnchorScore(span));
        assertEquals(17, span.getTradeDays());
    }

    /** 09-04：收 -9.01% 没到主板跌停线，但最低 -9.82% 还是触板 ⇒ 照样按跌停档 -3 分。 */
    @Test
    void closingAboveTheLineStillCountsWhenTheLowTouchedIt() {
        AnchorMetrics.Span span = AnchorMetrics.measure(haiyao(), START, null,
                LocalDate.of(2026, 9, 4), AnchorMetrics.LIMIT_MAIN);

        assertFalse(span.isCloseLimitDown());
        assertTrue(span.isTouchedLimitDown());
        assertEquals(Integer.valueOf(-3), TemperatureCalculator.calcAnchorScore(span));
        assertEquals(18, span.getTradeDays());
    }

    /** 08-21 是收盘跌停，判据走 pct 那一支；同时跨度最高收盘在它之前，回撤必须是负数。 */
    @Test
    void readsCloseLimitDownAndDrawdown() {
        AnchorMetrics.Span span = AnchorMetrics.measure(haiyao(), START, null,
                LocalDate.of(2026, 8, 21), AnchorMetrics.LIMIT_MAIN);

        assertTrue(span.isCloseLimitDown());
        assertEquals(Integer.valueOf(-3), TemperatureCalculator.calcAnchorScore(span));
        assertEquals(0, new BigDecimal("9.12").compareTo(span.getPeakClose()));
        assertTrue(span.getDrawdownPct().signum() < 0);
        assertFalse(span.isNewSpanHigh());
    }

    /** 连板数最长那一串；断板是"昨日涨停今日未涨停"，起点前那一根只用来比涨跌、不进跨度。 */
    @Test
    void countsStreaksAndBreakDates() {
        List<DayBar> bars = Arrays.asList(
                bar("2026-07-10", "5.00", null, null),
                bar("2026-07-13", "5.50", "10.00", "10.00"),
                bar("2026-07-14", "6.05", "10.00", "10.00"),
                bar("2026-07-15", "6.66", "10.08", "10.08"),
                bar("2026-07-16", "6.60", "-0.90", "-3.00"),
                bar("2026-07-17", "6.70", "1.52", "-1.00"));

        AnchorMetrics.Span span = AnchorMetrics.measure(bars, LocalDate.of(2026, 7, 13), null,
                LocalDate.of(2026, 7, 17), AnchorMetrics.LIMIT_MAIN);

        assertEquals(3, span.getMaxBoard());
        assertEquals(Arrays.asList(LocalDate.of(2026, 7, 16)), span.getBreakDates());
        assertEquals(LocalDate.of(2026, 7, 16), span.getLastBreakDate());
        assertFalse(span.isBrokeToday());
        assertEquals(5, span.getTradeDays());
    }

    /**
     * 新梯只有 3/2/0/-1/-2/-3 六个读数，<b>1 分这一档不存在</b>，别再按"每档都有"去读它。
     *
     * <p>{@code isNewSpanHigh} 这一位<b>不再加分</b>：+5.66% 那条确实创了跨度内最高收盘，
     * 可它仍然只算"收红"。创新高现在只是展示字段，谁以后要把它请回打分，得先改这里的断言。
     */
    @Test
    void scoresStrongRedRedGreenAndFlat() {
        List<DayBar> climb = Arrays.asList(
                bar("2026-07-13", "5.40", "1.00", "0.20"),
                bar("2026-07-14", "5.30", "-1.85", "-2.60"),
                bar("2026-07-15", "5.83", "10.00", "10.00"));

        AnchorMetrics.Span strongRed = AnchorMetrics.measure(climb, LocalDate.of(2026, 7, 13), null,
                LocalDate.of(2026, 7, 15), AnchorMetrics.LIMIT_MAIN);
        assertTrue(strongRed.isNewSpanHigh());
        assertEquals(Integer.valueOf(3), TemperatureCalculator.calcAnchorScore(strongRed));

        // 同一个位置换成 +5.66%：照样创新高，但没到 9.5% 那条强涨停线。
        List<DayBar> mildClimb = Arrays.asList(
                bar("2026-07-13", "5.40", "1.00", "0.20"),
                bar("2026-07-14", "5.30", "-1.85", "-2.60"),
                bar("2026-07-15", "5.60", "5.66", "0.10"));
        AnchorMetrics.Span mildHigh = AnchorMetrics.measure(mildClimb, LocalDate.of(2026, 7, 13), null,
                LocalDate.of(2026, 7, 15), AnchorMetrics.LIMIT_MAIN);
        assertTrue(mildHigh.isNewSpanHigh());
        assertEquals(Integer.valueOf(2), TemperatureCalculator.calcAnchorScore(mildHigh));

        AnchorMetrics.Span green = AnchorMetrics.measure(climb, LocalDate.of(2026, 7, 13), null,
                LocalDate.of(2026, 7, 14), AnchorMetrics.LIMIT_MAIN);
        assertFalse(green.isBrokeToday());
        assertEquals(Integer.valueOf(0), TemperatureCalculator.calcAnchorScore(green));

        AnchorMetrics.Span redNotHigh = AnchorMetrics.measure(Arrays.asList(
                        bar("2026-07-13", "9.50", "1.00", "0.50"),
                        bar("2026-07-14", "8.90", "-6.32", "-7.00"),
                        bar("2026-07-15", "9.10", "2.25", "0.10")), LocalDate.of(2026, 7, 13), null,
                LocalDate.of(2026, 7, 15), AnchorMetrics.LIMIT_MAIN);
        assertFalse(redNotHigh.isNewSpanHigh());
        assertEquals(Integer.valueOf(2), TemperatureCalculator.calcAnchorScore(redNotHigh));

        AnchorMetrics.Span flat = AnchorMetrics.measure(Arrays.asList(
                        bar("2026-07-13", "9.00", "1.00", "0.50"),
                        bar("2026-07-14", "9.00", "0.00", "-1.00")), LocalDate.of(2026, 7, 13), null,
                LocalDate.of(2026, 7, 14), AnchorMetrics.LIMIT_MAIN);
        assertEquals(Integer.valueOf(0), TemperatureCalculator.calcAnchorScore(flat));
    }

    /** 昨日涨停、今日未涨停 = 断板 = -1 分，哪怕今天收红：断板本身就是这一维最硬的负反馈。 */
    @Test
    void brokenStreakIsNegativeEvenOnARedDay() {
        AnchorMetrics.Span span = AnchorMetrics.measure(Arrays.asList(
                        bar("2026-07-13", "5.50", "10.00", "10.00"),
                        bar("2026-07-14", "5.60", "1.82", "0.20")), LocalDate.of(2026, 7, 13), null,
                LocalDate.of(2026, 7, 14), AnchorMetrics.LIMIT_MAIN);

        assertTrue(span.isBrokeToday());
        assertEquals(Integer.valueOf(-1), TemperatureCalculator.calcAnchorScore(span));
    }

    /** 断板又跌 6%：{@code ≤-5%} 那一支走在断板前面，所以读 -2 而不是 -1。 */
    @Test
    void aCrashOnABrokenStreakReadsTheCrashNotTheBreak() {
        AnchorMetrics.Span span = AnchorMetrics.measure(Arrays.asList(
                        bar("2026-07-13", "5.50", "10.00", "10.00"),
                        bar("2026-07-14", "5.17", "-6.00", "-6.50")), LocalDate.of(2026, 7, 13), null,
                LocalDate.of(2026, 7, 14), AnchorMetrics.LIMIT_MAIN);

        assertTrue(span.isBrokeToday());
        assertEquals(Integer.valueOf(-2), TemperatureCalculator.calcAnchorScore(span));
    }

    /** 停牌那天没有柱子：未评，不是 0 分。跨度长度只数已到的柱子，不能顺带报成 0 天。 */
    @Test
    void aDayWithoutABarIsNotRatedRatherThanZero() {
        List<DayBar> bars = Arrays.asList(
                bar("2026-07-13", "5.50", "10.00", "10.00"),
                bar("2026-07-14", "5.60", "1.82", "0.20"));

        AnchorMetrics.Span span = AnchorMetrics.measure(bars, LocalDate.of(2026, 7, 13), null,
                LocalDate.of(2026, 7, 15), AnchorMetrics.LIMIT_MAIN);

        assertFalse(span.isAvailable());
        assertNull(TemperatureCalculator.calcAnchorScore(span));
        assertNull(span.getPct());
        assertEquals(2, span.getTradeDays());
    }

    /** 一根都没拉到（请求失败）：不能出现"最高 0 板"这种看着很正常的假象。 */
    @Test
    void anEmptySeriesMeasuresNothing() {
        AnchorMetrics.Span span = AnchorMetrics.measure(new ArrayList<DayBar>(), START, null,
                LocalDate.of(2026, 9, 4), AnchorMetrics.LIMIT_MAIN);

        assertFalse(span.isAvailable());
        assertEquals(0, span.getTradeDays());
        assertEquals(0, span.getMaxBoard());
        assertNull(span.getPeakClose());
        assertNull(TemperatureCalculator.calcAnchorScore(span));
    }

    /** 第一天没有前收：跨度能出，但那天涨跌无从判起 ⇒ 未评，而不是"平盘 0 分"。 */
    @Test
    void firstDayWithoutPreviousCloseIsNotRated() {
        AnchorMetrics.Span span = AnchorMetrics.measure(
                Arrays.asList(bar("2026-07-13", "5.50", null, null)), LocalDate.of(2026, 7, 13), null,
                LocalDate.of(2026, 7, 13), AnchorMetrics.LIMIT_MAIN);

        assertTrue(span.isAvailable());
        assertNull(span.getPct());
        assertNull(TemperatureCalculator.calcAnchorScore(span));
    }

    /** 阈值来自板块：创业板的 -9.96% 只是普通下跌，主板同样数字是触板。 */
    @Test
    void thresholdsComeFromTheBoard() {
        assertEquals(0, new BigDecimal("19.8").compareTo(AnchorMetrics.limitOf("创业板")));
        assertEquals(0, new BigDecimal("19.8").compareTo(AnchorMetrics.limitOf("科创板")));
        assertEquals(0, new BigDecimal("29.8").compareTo(AnchorMetrics.limitOf("北交所")));
        assertEquals(0, new BigDecimal("9.8").compareTo(AnchorMetrics.limitOf("沪主板")));
        assertEquals(0, new BigDecimal("9.8").compareTo(AnchorMetrics.limitOf(null)));

        List<DayBar> bars = Arrays.asList(
                bar("2026-09-02", "9.24", "-2.63", "-3.27"),
                bar("2026-09-03", "8.55", "-7.47", "-9.96"));
        AnchorMetrics.Span main = AnchorMetrics.measure(bars, LocalDate.of(2026, 9, 2), null,
                LocalDate.of(2026, 9, 3), AnchorMetrics.limitOf("沪主板"));
        AnchorMetrics.Span growth = AnchorMetrics.measure(bars, LocalDate.of(2026, 9, 2), null,
                LocalDate.of(2026, 9, 3), AnchorMetrics.limitOf("创业板"));

        // 主板：盘中触到 -9.8% ⇒ 跌停档。
        assertTrue(main.isTouchedLimitDown());
        assertEquals(Integer.valueOf(-3), TemperatureCalculator.calcAnchorScore(main));
        // 创业板：-9.96% 连 19.8% 的一半都不到，可 -7.47% 的收盘已经过了"按核"那条 -5% 线。
        assertFalse(growth.isTouchedLimitDown());
        assertEquals(Integer.valueOf(-2), TemperatureCalculator.calcAnchorScore(growth));
    }

    /** 多只在位取最差：阵眼是哨兵，一只崩了这轮就是负反馈，取平均正好把它稀释掉。 */
    @Test
    void worstAnchorScoreTakesTheMinimumAndIgnoresUnrated() {
        AnchorMetrics.Span red = dayOf("5.60", "1.82", "0.20");
        AnchorMetrics.Span crash = dayOf("5.00", "-10.00", "-10.00");
        AnchorMetrics.Span noBar = AnchorMetrics.measure(new ArrayList<DayBar>(), START, null,
                LocalDate.of(2026, 9, 4), AnchorMetrics.LIMIT_MAIN);

        assertEquals(Integer.valueOf(-3), TemperatureCalculator.worstAnchorScore(
                Arrays.asList(red, crash)));
        assertEquals(Integer.valueOf(-3), TemperatureCalculator.worstAnchorScore(
                Arrays.asList(red, noBar, crash)));
        assertEquals(Integer.valueOf(2), TemperatureCalculator.worstAnchorScore(
                Arrays.asList(red, noBar)));
        assertNull(TemperatureCalculator.worstAnchorScore(Arrays.asList(noBar)));
        assertNull(TemperatureCalculator.worstAnchorScore(null));
    }

    /** 高点在前、当日收在低位的三天序列：收红但不创新高，专门用来喂"取最差"那一条。 */
    private static AnchorMetrics.Span dayOf(String close, String pct, String lowPct) {
        List<DayBar> bars = Arrays.asList(
                bar("2026-09-02", "9.00", "1.00", "0.50"),
                bar("2026-09-03", "5.50", "-2.00", "-3.00"),
                bar("2026-09-04", close, pct, lowPct));
        return AnchorMetrics.measure(bars, LocalDate.of(2026, 9, 2), null,
                LocalDate.of(2026, 9, 4), AnchorMetrics.LIMIT_MAIN);
    }
}
