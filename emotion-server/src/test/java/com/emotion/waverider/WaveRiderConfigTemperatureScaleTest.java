package com.emotion.waverider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 情绪温度 → 建议仓位系数（PRD §6 / §17 {@code position_scale_by_temperature}）。
 *
 * <p>只钉这个阶梯函数本身与它的合法性校验，不跑引擎：引擎要连一堆 mapper，
 * 而这里最容易写错的是三条——档位边界算不算含、温度低于最低档时会不会掉成 0、
 * 以及 JSON 里键写乱了还认不认。
 *
 * <p>断言用的温度值全是库里的真实取值（2026-09 那批 t_daily_record），不是编的整数。
 */
class WaveRiderConfigTemperatureScaleTest {

    private final WaveRiderConfig cfg = new WaveRiderConfig();

    private static Map<String, Double> tiers(String... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], Double.valueOf(kv[i + 1]));
        }
        return m;
    }

    private static boolean mentions(List<String> errs, String key) {
        for (String e : errs) {
            if (e.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** PRD 的四档：<55 → 0.5、55~60 → 0.6、60~70 → 0.8、≥70 → 1.0。 */
    @Test
    void prdTiersOnRealTemperatures() {
        assertEquals(0.5, cfg.temperatureScale(42.5), 1e-9);   // 09-04 冰点修复日
        assertEquals(0.5, cfg.temperatureScale(52.8), 1e-9);   // 09-30
        assertEquals(0.6, cfg.temperatureScale(59.7), 1e-9);   // 09-29
        assertEquals(0.8, cfg.temperatureScale(68.7), 1e-9);   // 09-21
        assertEquals(1.0, cfg.temperatureScale(77.2), 1e-9);   // 09-18
    }

    /** 下界含、上界不含：55 整落进 55~60 那档，不是 <55 那档。 */
    @Test
    void tierLowerBoundIsInclusive() {
        assertEquals(0.5, cfg.temperatureScale(54.999), 1e-9);
        assertEquals(0.6, cfg.temperatureScale(55.0), 1e-9);
        assertEquals(0.6, cfg.temperatureScale(59.999), 1e-9);
        assertEquals(0.8, cfg.temperatureScale(60.0), 1e-9);
        assertEquals(0.8, cfg.temperatureScale(69.999), 1e-9);
        assertEquals(1.0, cfg.temperatureScale(70.0), 1e-9);
    }

    /**
     * 「只降不做 0」：温度低于最小下界时退回最低档，而不是掉成 0。
     *
     * <p>§12.4.6 实测「低温就不做」在样本内是负贡献——它剔掉的日子里含 09-04（42.5°）
     * 那种冰点修复日，而冰点期恰恰是身位板赔率最高的位置。
     */
    @Test
    void belowLowestTierFallsBackToLowestNotZero() {
        assertEquals(0.5, cfg.temperatureScale(0.0), 1e-9);
        assertEquals(0.5, cfg.temperatureScale(-20.0), 1e-9);

        // 最低档不是 0 时同理：10° 该落进「55 以下」那档的 0.3，不是 0。
        cfg.setPositionScaleByTemperature(tiers("55", "0.3", "70", "1.0"));
        assertEquals(0.3, cfg.temperatureScale(10.0), 1e-9);
    }

    /** 键按数值排，不按 JSON 里的书写顺序——配置是给人手改的，不能指望别人一定升序写。 */
    @Test
    void keyOrderInJsonDoesNotMatter() {
        cfg.setPositionScaleByTemperature(tiers("70", "1.0", "0", "0.5", "60", "0.8", "55", "0.6"));
        assertEquals(0.5, cfg.temperatureScale(52.8), 1e-9);
        assertEquals(0.6, cfg.temperatureScale(59.7), 1e-9);
        assertEquals(0.8, cfg.temperatureScale(68.7), 1e-9);
        assertEquals(1.0, cfg.temperatureScale(77.2), 1e-9);
    }

    /** 小数下界也认：阶梯函数不限整数档位。 */
    @Test
    void fractionalThresholdsWork() {
        cfg.setPositionScaleByTemperature(tiers("0", "0.4", "57.5", "0.9"));
        assertEquals(0.4, cfg.temperatureScale(57.4), 1e-9);
        assertEquals(0.9, cfg.temperatureScale(57.5), 1e-9);
    }

    /** 空映射＝不做温度调节，而不是把仓位打成 0。 */
    @Test
    void emptyTiersMeanNoAdjustment() {
        cfg.setPositionScaleByTemperature(new LinkedHashMap<String, Double>());
        assertEquals(1.0, cfg.temperatureScale(30.0), 1e-9);
    }

    @Test
    void defaultConfigPassesValidation() {
        assertTrue(cfg.validate().isEmpty(), "出厂默认配置应当合法，实得：" + cfg.validate());
    }

    /** 系数 0 等于把温度做成了「做不做」的开关，PRD 明写只降不做 0。 */
    @Test
    void validateRejectsZeroScale() {
        cfg.setPositionScaleByTemperature(tiers("0", "0.0", "70", "1.0"));
        assertTrue(mentions(cfg.validate(), "position_scale_by_temperature"), cfg.validate().toString());
    }

    @Test
    void validateRejectsScaleAboveOne() {
        cfg.setPositionScaleByTemperature(tiers("0", "0.5", "70", "1.2"));
        assertTrue(mentions(cfg.validate(), "position_scale_by_temperature"), cfg.validate().toString());
    }

    @Test
    void validateRejectsNonNumericKey() {
        cfg.setPositionScaleByTemperature(tiers("冷", "0.5", "70", "1.0"));
        assertTrue(mentions(cfg.validate(), "position_scale_by_temperature"), cfg.validate().toString());
    }

    @Test
    void validateRejectsEmptyTiers() {
        cfg.setPositionScaleByTemperature(new LinkedHashMap<String, Double>());
        assertTrue(mentions(cfg.validate(), "position_scale_by_temperature"), cfg.validate().toString());
    }

    /**
     * 存量快照里没写过这个键时，读出来该是 PRD 那四档，不是 null。
     *
     * <p>走真正的读路径（Jackson 反序列化），不是 {@code new}：版本快照不可变、也不回写，
     * 补默认值只能发生在「读的时候认得」这一步。
     */
    @Test
    void absentKeyFallsBackToPrdDefaults() throws Exception {
        WaveRiderConfig legacy = new ObjectMapper()
                .readValue("{\"max_position_per_stock\":0.05,\"score_weights\":{\"board\":0.5}}",
                        WaveRiderConfig.class);
        assertEquals(WaveRiderConfig.defaultTemperatureScales(), legacy.getPositionScaleByTemperature());
        assertEquals(0.5, legacy.temperatureScale(52.8), 1e-9);
    }
}
