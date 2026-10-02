package com.emotion.market;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.emotion.entity.MarketStock;

class HighControlStockTest {

    private static MarketStock row(int board, int firstSealTime, int breakCount,
                                   double turnoverRate, BigDecimal floatMv) {
        MarketStock m = new MarketStock();
        m.setConsecutive(board);
        m.setFirstSealTime(firstSealTime);
        m.setBreakCount(breakCount);
        m.setTurnoverRate(BigDecimal.valueOf(turnoverRate));
        m.setFloatMv(floatMv);
        return m;
    }

    @Test
    void lockedWhenEarlySealAndZeroBreaks() {
        MarketStock m = row(3, 93030, 0, 2.0, null);
        assertTrue(HighControlStock.isLocked(m));
    }

    @Test
    void notLockedWhenLateSeal() {
        MarketStock m = row(3, 93031, 0, 2.0, null);
        assertFalse(HighControlStock.isLocked(m));
    }

    @Test
    void notLockedWhenAnyBreak() {
        MarketStock m = row(3, 92500, 1, 2.0, null);
        assertFalse(HighControlStock.isLocked(m));
    }

    @Test
    void allFourConditionsMet() {
        BigDecimal mv = new BigDecimal("3000000000");
        MarketStock m = row(3, 93000, 0, 3.0, mv);
        assertTrue(HighControlStock.isHighControl(m, mv));
    }

    @Test
    void boardBelowTwoFails() {
        BigDecimal mv = new BigDecimal("3000000000");
        MarketStock m = row(1, 93000, 0, 3.0, mv);
        assertFalse(HighControlStock.isHighControl(m, mv));
    }

    @Test
    void turnoverAtFiveFails() {
        BigDecimal mv = new BigDecimal("3000000000");
        MarketStock m = row(3, 93000, 0, 5.0, mv);
        assertFalse(HighControlStock.isHighControl(m, mv));
    }

    @Test
    void notLockedFails() {
        BigDecimal mv = new BigDecimal("3000000000");
        MarketStock m = row(3, 100000, 0, 3.0, mv);
        assertFalse(HighControlStock.isHighControl(m, mv));
    }

    @Test
    void floatMvAboveCapFails() {
        BigDecimal mv = new BigDecimal("3500000001");
        MarketStock m = row(3, 93000, 0, 3.0, mv);
        assertFalse(HighControlStock.isHighControl(m, mv));
    }

    @Test
    void floatMvExactlyAtCapPasses() {
        BigDecimal mv = new BigDecimal("3500000000");
        MarketStock m = row(3, 93000, 0, 3.0, mv);
        assertTrue(HighControlStock.isHighControl(m, mv));
    }

    @Test
    void nullFirstBoardFloatMvFailsOpen() {
        MarketStock m = row(3, 93000, 0, 3.0, new BigDecimal("3000000000"));
        assertFalse(HighControlStock.isHighControl(m, null));
    }

    @Test
    void nullRowFails() {
        assertFalse(HighControlStock.isHighControl(null, new BigDecimal("3000000000")));
    }
}
