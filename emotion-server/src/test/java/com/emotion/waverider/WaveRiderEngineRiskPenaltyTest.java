package com.emotion.waverider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.emotion.entity.MarketStock;

/**
 * 风险扣分系数的分档——评分公式里 {@code −W_risk × 系数} 的那个系数。
 *
 * <p>只钉系数本身，不跑引擎：引擎要连一堆 mapper，而这里最容易写错的是两条——
 * 「高控庄票必须比一字缩量狠」和「高控庄票不与缩量一字相加成 3」。
 * 用默认配置：W_risk=0.20、filter_min_amount=1.0 亿。
 */
class WaveRiderEngineRiskPenaltyTest {

    private final WaveRiderConfig cfg = new WaveRiderConfig();

    private static MarketStock row(double turnoverRate, double amountYi) {
        MarketStock m = new MarketStock();
        m.setTurnoverRate(BigDecimal.valueOf(turnoverRate));
        m.setAmount(BigDecimal.valueOf(amountYi * 1e8));
        return m;
    }

    @Test
    void noRiskScoresZero() {
        assertEquals(0.0, WaveRiderEngine.riskPenalty(row(12.0, 3.0), cfg, false), 1e-9);
    }

    /** 缩量一字：换手 &lt; 2% 且成交额低于 1.0 亿下限，两条都要满足。 */
    @Test
    void yiziThinScoresOne() {
        assertEquals(1.0, WaveRiderEngine.riskPenalty(row(1.5, 0.6), cfg, false), 1e-9);
    }

    /** 换手低但成交额不小 → 不是缩量一字，只是没人炒，不扣分。 */
    @Test
    void thinTurnoverWithHealthyAmountIsNotYizi() {
        assertEquals(0.0, WaveRiderEngine.riskPenalty(row(1.5, 3.0), cfg, false), 1e-9);
    }

    /** 成交额低于下限但换手不低 → 也不算，缩量与一字缺一不可。 */
    @Test
    void smallAmountWithNormalTurnoverIsNotYizi() {
        assertEquals(0.0, WaveRiderEngine.riskPenalty(row(12.0, 0.6), cfg, false), 1e-9);
    }

    @Test
    void highTurnoverScoresOne() {
        assertEquals(1.0, WaveRiderEngine.riskPenalty(row(35.0, 3.0), cfg, false), 1e-9);
    }

    /** 高控庄票单独一档，比普通风险项狠一倍。 */
    @Test
    void highControlIsHarsherThanOrdinaryRisk() {
        double high = WaveRiderEngine.riskPenalty(row(3.0, 0.5), cfg, true);
        double thin = WaveRiderEngine.riskPenalty(row(1.5, 0.5), cfg, false);
        assertEquals(2.0, high, 1e-9);
        assertEquals(1.0, thin, 1e-9);
        assertTrue(high > thin, "高控庄票必须比一字缩量扣得多");
    }

    /**
     * 高控庄票通常也满足缩量一字（一字锁死的票换手极低），此时取高档那一个值，
     * 不相加成 3。改成相加就会悄悄多扣 0.20，这条钉住它。
     */
    @Test
    void highControlDoesNotStackWithYiziThin() {
        assertEquals(2.0, WaveRiderEngine.riskPenalty(row(1.5, 0.4), cfg, true), 1e-9);
    }

    /**
     * 系数允许 &gt;1，所以得分可能为负——这是有意的，仓位那侧对 {@code ratio<=0}
     * 另有 0.1 的地板。这里钉住「两倍」在现行权重下到底是扣多少，
     * 改 W_risk 时这条会红，提醒顺带核对界面上的文案。
     */
    @Test
    void highControlDeductsFortyPercentOfCeiling() {
        double deduction = cfg.getScoreWeights().get("risk") * WaveRiderEngine.HIGH_CONTROL_PENALTY;
        assertEquals(0.40, deduction, 1e-9);
        double ceiling = cfg.getScoreWeights().get("board") + cfg.getScoreWeights().get("position")
                + cfg.getScoreWeights().get("node") + cfg.getScoreWeights().get("timing");
        assertEquals(1.10, ceiling, 1e-9);
        assertTrue(deduction < ceiling, "扣分不能吃满整个满分，否则堆叠条会溢出");
    }
}
