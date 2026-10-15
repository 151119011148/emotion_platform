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

import com.emotion.entity.MarketStock;

/**
 * 「高控庄票」判据。
 *
 * <p>五条腿各钉一条反例，另加三段真实行情（生产 {@code t_market_stock} 2026-09～10 月的行）：
 * 雪龙集团 09-29 必须命中（这是打标的原型），慈文传媒 10-13、新经典 10-13 必须不命中
 * ——它们是游资题材连板，不是庄控。老判据四条腿时这两个都带着标，2026-10-15 收紧。
 */
class HighControlStockTest {

    private static final BigDecimal YI = new BigDecimal("100000000");
    private static final LocalDate D = LocalDate.of(2026, 10, 9);

    private static MarketStock row(LocalDate date, int board, Integer firstSealTime, Integer breakCount,
                                   double turnoverRate, double amountYi, BigDecimal floatMv) {
        MarketStock m = new MarketStock();
        m.setTradeDate(date);
        m.setPool(MarketStock.POOL_LIMIT_UP);
        m.setConsecutive(board);
        m.setFirstSealTime(firstSealTime);
        m.setBreakCount(breakCount);
        m.setTurnoverRate(BigDecimal.valueOf(turnoverRate));
        m.setAmount(BigDecimal.valueOf(amountYi).multiply(YI));
        m.setFloatMv(floatMv);
        return m;
    }

    /** 当天这条是标准高控庄：3 板、一字锁死、换手 1.5%、成交 0.6 亿、段内 2 天锁死、首板 30 亿。 */
    private static MarketStock hit() {
        return row(D, 3, 93000, 0, 1.5, 0.6, new BigDecimal("3000000000"));
    }

    private static HighControlStock.Streak streak(int days, int lockedDays, String firstBoardMvYi) {
        return new HighControlStock.Streak(
                firstBoardMvYi == null ? null : new BigDecimal(firstBoardMvYi).multiply(YI),
                days, lockedDays);
    }

    private static final HighControlStock.Streak OK_STREAK = streak(3, 2, "30");

    @Test
    void lockedWhenEarlySealAndZeroBreaks() {
        assertTrue(HighControlStock.isLocked(row(D, 3, 93030, 0, 2.0, 0.6, null)));
    }

    @Test
    void notLockedWhenLateSeal() {
        assertFalse(HighControlStock.isLocked(row(D, 3, 93031, 0, 2.0, 0.6, null)));
    }

    @Test
    void notLockedWhenAnyBreak() {
        assertFalse(HighControlStock.isLocked(row(D, 3, 92500, 1, 2.0, 0.6, null)));
    }

    @Test
    void allFiveLegsMet() {
        assertTrue(HighControlStock.isHighControl(hit(), OK_STREAK));
    }

    @Test
    void boardBelowTwoFails() {
        assertFalse(HighControlStock.isHighControl(row(D, 1, 93000, 0, 1.5, 0.6, null), OK_STREAK));
    }

    /** 一字锁死这条腿不能少：缩量但不是板锁着的，不算控盘。 */
    @Test
    void notLockedFails() {
        assertFalse(HighControlStock.isHighControl(row(D, 3, 100000, 0, 1.5, 0.6, null), OK_STREAK));
    }

    /**
     * 换手 5% 是收紧前老判据的上限，正是它把慈文（3.37%）、新经典（2.79%）、紫竹（2.88%）
     * 这批放量题材票放进来的一条腿。现在跟 PRD 的缩量一字同档：&lt;2%。
     */
    @Test
    void turnoverAtTwoFails() {
        assertFalse(HighControlStock.isHighControl(row(D, 3, 93000, 0, 2.0, 0.6, null), OK_STREAK));
    }

    @Test
    void turnoverJustBelowTwoPasses() {
        assertTrue(HighControlStock.isHighControl(row(D, 3, 93000, 0, 1.99, 0.6, null), OK_STREAK));
    }

    /** 地量这条腿：换手低但成交额上亿，是「没人炒」或「放量抢上来」，不是锁筹。 */
    @Test
    void amountAboveOneYiFails() {
        assertFalse(HighControlStock.isHighControl(row(D, 3, 93000, 0, 1.5, 1.01, null), OK_STREAK));
    }

    @Test
    void amountExactlyOneYiPasses() {
        assertTrue(HighControlStock.isHighControl(row(D, 3, 93000, 0, 1.5, 1.0, null), OK_STREAK));
    }

    /** 成交额取不到（历史行可能为 null）时 fail-open，不猜。 */
    @Test
    void nullAmountFailsOpen() {
        MarketStock m = hit();
        m.setAmount(null);
        assertFalse(HighControlStock.isHighControl(m, OK_STREAK));
    }

    /**
     * 持续性这条腿：段内只有一天一字锁死＝今天蒙上了，老判据就是这么宽的。
     * 百洋 10-12、三柏硕 10-09、冰山冷热 10-14、紫竹 09-30 全在这一档出局。
     */
    @Test
    void singleLockedDayInStreakFails() {
        assertFalse(HighControlStock.isHighControl(hit(), streak(2, 1, "30")));
    }

    @Test
    void firstBoardMvAboveCapFails() {
        assertFalse(HighControlStock.isHighControl(hit(), streak(3, 2, "35.01")));
    }

    @Test
    void firstBoardMvExactlyAtCapPasses() {
        assertTrue(HighControlStock.isHighControl(hit(), streak(3, 2, "35")));
    }

    @Test
    void nullFirstBoardMvFailsOpen() {
        assertFalse(HighControlStock.isHighControl(hit(), streak(3, 2, null)));
    }

    @Test
    void nullRowOrNullStreakFails() {
        assertFalse(HighControlStock.isHighControl(null, OK_STREAK));
        assertFalse(HighControlStock.isHighControl(hit(), null));
    }

    // ------------------------------------------------ 生产实据：该中的中，该掉的黑不掉

    /** 雪龙集团 2026-09-29：4 板、换手 1.67%、成交 0.72 亿、首板日 32.29 亿、段内 2 天锁死。 */
    @Test
    void xuelongStillTagged() {
        MarketStock m = row(D, 4, 92501, 0, 1.67, 0.72, new BigDecimal("4300000000"));
        assertTrue(HighControlStock.isHighControl(m, streak(4, 2, "32.29")));
    }

    /** 慈文传媒 2026-10-13：换手 3.37%、成交 1.175 亿——放量题材连板，换手与地量两条都不过。 */
    @Test
    void ciwenNotTagged() {
        MarketStock m = row(D, 3, 92500, 0, 3.37, 1.175, new BigDecimal("3490000000"));
        assertFalse(HighControlStock.isHighControl(m, streak(3, 2, "28.8")));
    }

    /** 新经典 2026-10-13：成交 0.9 亿过了，换手 2.79% 不过——主力成交占比 0.69% 的家族锁筹不是庄控。 */
    @Test
    void xinjingdianNotTagged() {
        MarketStock m = row(D, 3, 92500, 0, 2.79, 0.901, new BigDecimal("3230000000"));
        assertFalse(HighControlStock.isHighControl(m, streak(3, 2, "26.73")));
    }

    // ------------------------------------------------ streakOf：段长与段内锁死天数

    @Test
    void streakCountsWholeBoardAndLockedDays() {
        List<MarketStock> rows = Arrays.asList(
                row(D.minusDays(4), 1, 140035, 0, 2.80, 0.71, new BigDecimal("2670000000")),
                row(D.minusDays(2), 2, 92501, 0, 2.41, 0.71, new BigDecimal("2940000000")),
                row(D, 3, 92500, 0, 2.79, 0.90, new BigDecimal("3230000000")));
        HighControlStock.Streak s = HighControlStock.streakOf(rows, D);
        assertEquals(3, s.days());
        // 首板是 14:00 的尾盘板，只有两天锁死
        assertEquals(2, s.lockedDays());
        assertEquals(new BigDecimal("2670000000"), s.firstBoardFloatMv());
    }

    /** 首板日市值取窗口内最近那条 consecutive=1，跨段也不含糊（老口径原样保留）。 */
    @Test
    void firstBoardMvIsLatestSingleBoardRow() {
        List<MarketStock> rows = Arrays.asList(
                row(D.minusDays(20), 1, 92500, 0, 3.0, 0.5, new BigDecimal("2000000000")),
                row(D.minusDays(2), 1, 92500, 0, 3.0, 0.5, new BigDecimal("2600000000")),
                row(D, 2, 92500, 0, 1.5, 0.6, new BigDecimal("2800000000")));
        assertEquals(new BigDecimal("2600000000"), HighControlStock.streakOf(rows, D).firstBoardFloatMv());
    }

    /** 断板（段里缺一级板数）就停：往后的日子再像也不认，段算短、锁死天数只多不少算。 */
    @Test
    void brokenStreakStopsAtTheGap() {
        List<MarketStock> rows = Arrays.asList(
                row(D.minusDays(6), 1, 92500, 0, 1.5, 0.5, new BigDecimal("2000000000")),
                row(D.minusDays(4), 2, 92500, 0, 1.5, 0.5, new BigDecimal("2200000000")),
                row(D, 4, 92500, 0, 1.5, 0.6, new BigDecimal("2400000000")));
        HighControlStock.Streak s = HighControlStock.streakOf(rows, D);
        assertEquals(1, s.days());
        assertEquals(1, s.lockedDays());
    }

    @Test
    void missingTodayYieldsEmptyStreak() {
        List<MarketStock> rows = new ArrayList<>();
        rows.add(row(D.minusDays(1), 1, 92500, 0, 1.5, 0.5, new BigDecimal("2000000000")));
        HighControlStock.Streak s = HighControlStock.streakOf(rows, D);
        assertEquals(0, s.days());
        assertEquals(0, s.lockedDays());
        assertNull(HighControlStock.streakOf(null, D).firstBoardFloatMv());
    }

    /** 超过 45 天回溯窗口的首板不算——那是另一个故事了。 */
    @Test
    void firstBoardOutsideLookbackIsIgnored() {
        List<MarketStock> rows = Arrays.asList(
                row(D.minusDays(HighControlStock.LOOKBACK_DAYS + 3), 1, 92500, 0, 1.5, 0.5,
                        new BigDecimal("2000000000")),
                row(D, 2, 92500, 0, 1.5, 0.6, new BigDecimal("2400000000")));
        assertNull(HighControlStock.streakOf(rows, D).firstBoardFloatMv());
    }
}
