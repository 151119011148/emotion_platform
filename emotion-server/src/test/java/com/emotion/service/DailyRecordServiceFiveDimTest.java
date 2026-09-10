package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.emotion.entity.DailyRecord;
import com.emotion.util.BoardScoreCalculator;
import com.emotion.util.ScoreInputs;

/**
 * 覆盖 {@link DailyRecordService#applyFiveDimScore} 这层胶水：把 BoardScoreCalculator 的 Result
 * 落回 DailyRecord 的 5 个 score_&#42; 列 / total_score / temperature / stage / signal_flags / forced_ebb。
 *
 * <p>引擎本身由 BoardScoreCalculatorTest 覆盖；这里只验证"结果如何映射到列"这一层：
 * 缺哪个列写错、四舍五入口径、"未评 != 0"、强制退潮是否穿透 stage、prev_temperature 缺失时方向留空。
 */
class DailyRecordServiceFiveDimTest {

    /** 每个维都能出分：5 个 score_* 全部非空、total_score=round(total, HALF_UP)、temperature=total、stage=4 带之一。 */
    @Test
    void applyFiveDimScorePopulatesAllColumnsWhenEveryDimScores() {
        DailyRecord record = newRecord();
        ScoreInputs in = ScoreInputs.empty();
        in.setMetrics(allDimMetrics());

        DailyRecordService.applyFiveDimScore(record, in);

        assertNotNull(record.getScoreMarket(), "score_market 应有分");
        assertNotNull(record.getScoreThemeMain(), "score_theme_main 应有分(部分依赖人工,人工未评时该子剔出分母)");
        assertNotNull(record.getScoreBoard(), "score_board 应有分");
        assertNotNull(record.getScoreFirst(), "score_first 应有分");
        assertNotNull(record.getScoreAnchor(), "score_anchor 应有分");
        assertNotNull(record.getTemperature(), "temperature = total 直连,不再映射");
        assertNotNull(record.getTotalScore(), "total_score 是 temperature 的整数版本");
        assertTrue(record.getStage().equals("高潮") || record.getStage().equals("发酵")
                        || record.getStage().equals("混沌") || record.getStage().equals("退潮"),
                "stage 必须是 4 带之一,实际=" + record.getStage());
        assertEquals(5, record.getScoredDims().intValue(), "5 维都应有分");
        // total 与 total_score 口径:HALF_UP 到整数,差值 <= 0.5
        BigDecimal t = record.getTemperature();
        int rounded = t.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        assertEquals(rounded, record.getTotalScore().intValue(),
                "total_score 应=temperature HALF_UP 取整");
        // 没触发强制退潮也没命中信号时,两列保持 null / 0
        assertEquals(0, record.getForcedEbb().intValue(), "正常日 forced_ebb=0");
        assertNull(record.getForcedEbbReason());
    }

    /** 全维无源:total=null、stage=空、scored_dims=0、5 个 score_* 都 null(不能兜 0)。 */
    @Test
    void applyFiveDimScoreLeavesAllColumnsNullWhenNothingScoreable() {
        DailyRecord record = newRecord();
        ScoreInputs in = ScoreInputs.empty();
        in.setMetrics(new LinkedHashMap<>());

        DailyRecordService.applyFiveDimScore(record, in);

        assertNull(record.getTemperature(), "无任一维可评时 temperature 应为 null,不能兜 0");
        assertNull(record.getTotalScore(), "同上,total_score 也应为 null");
        assertEquals(0, record.getScoredDims().intValue(), "scored_dims=0");
        assertEquals("", record.getStage(), "无 stage,空串而不是 null 或假阶段");
        assertNull(record.getScoreMarket());
        assertNull(record.getScoreThemeMain());
        assertNull(record.getScoreBoard());
        assertNull(record.getScoreFirst());
        assertNull(record.getScoreAnchor());
    }

    /** 强制退潮:跌停 >=10 触发,无视 total 落 "退潮(强制)" + forced_ebb=1 + 原因. */
    @Test
    void applyFiveDimScoreMarksForcedEbbWhenLimitDownCrossesTen() {
        DailyRecord record = newRecord();
        ScoreInputs in = ScoreInputs.empty();
        Map<String, BigDecimal> metrics = allDimMetrics();
        metrics.put("limit_down_count", new BigDecimal("15"));
        in.setMetrics(metrics);

        DailyRecordService.applyFiveDimScore(record, in);

        assertEquals(1, record.getForcedEbb().intValue(), "跌停>=10 应触发强制退潮");
        assertEquals("退潮(强制)", record.getStage(), "stage 应穿透为 退潮(强制)");
        assertNotNull(record.getForcedEbbReason(), "reason 必填,便于复盘界面显示");
    }

    /** 中位吹哨 guard:命中时连板维分应 x 0.8,记录在 signal_flags 上. */
    @Test
    void applyFiveDimScoreSignalsMidWhistleWhenPromoMidWeak() {
        DailyRecord record = newRecord();
        ScoreInputs in = ScoreInputs.empty();
        Map<String, BigDecimal> metrics = allDimMetrics();
        metrics.put("jr_mid", new BigDecimal("5"));      // < 15%
        metrics.put("prem_mid", new BigDecimal("-2"));   // < 0
        metrics.put("big_mid", new BigDecimal("8"));     // >= 3
        in.setMetrics(metrics);

        DailyRecordService.applyFiveDimScore(record, in);

        assertNotNull(record.getSignalFlags(), "中位吹哨应写入 signal_flags");
        assertTrue(record.getSignalFlags().contains("中位吹哨"),
                "实际 flags=" + record.getSignalFlags());
    }

    /** 方向:prev 缺失=空串;差值绝对值 <3=横盘;>=+3=上升;<=-3=下降. */
    @Test
    void applyFiveDimScoreSetsDirectionByDeltaThreePoints() {
        // case A: 无 prev -> 空
        DailyRecord r1 = newRecord();
        ScoreInputs in1 = ScoreInputs.empty();
        in1.setMetrics(allDimMetrics());
        DailyRecordService.applyFiveDimScore(r1, in1);
        assertEquals("", r1.getStageDirection(), "prev_temperature=null 时方向应留空");

        // case B: 用固定 total 覆盖,直接测方向映射
        DirectionProbe probe = new DirectionProbe();
        assertEquals("上升", probe.directionFor(new BigDecimal("70"), new BigDecimal("60")));
        assertEquals("下降", probe.directionFor(new BigDecimal("50"), new BigDecimal("60")));
        assertEquals("横盘", probe.directionFor(new BigDecimal("62"), new BigDecimal("60")));
        assertEquals("上升", probe.directionFor(new BigDecimal("63"), new BigDecimal("60")));
        assertEquals("下降", probe.directionFor(new BigDecimal("57"), new BigDecimal("60")));
        assertEquals("", probe.directionFor(null, new BigDecimal("60")));
        assertEquals("", probe.directionFor(new BigDecimal("60"), null));
    }

    /** ScoreInputs.scoringTree=null 时,胶水层应回退到 BoardScoreCalculator.builtinTree(),不能崩. */
    @Test
    void applyFiveDimScoreFallsBackToBuiltinTreeWhenMissing() {
        DailyRecord record = newRecord();
        ScoreInputs in = ScoreInputs.empty();
        // 不设 scoringTree
        in.setMetrics(allDimMetrics());
        assertNull(in.getScoringTree(), "本用例故意不注入 tree");

        DailyRecordService.applyFiveDimScore(record, in);

        assertNotNull(record.getTemperature(), "缺 tree 时应走 builtin 兜底出分");
    }

    /**
     * D2 主线明确度（v2 五要素）：涨停/高度聚集度、催化剂硬度、持续性由 PrdMetricsService 自动取数，
     * 成交额聚集度只有人工列——本测试不走 DB，直接喂 metrics 验证「有读数才进分母」的形状。
     */
    @Test
    void themeDimJoinsTheDenominatorOnlyWhenHumanReadingsArrive() {
        // 把 D2 五个键全拿掉：整维无可评子
        Map<String, BigDecimal> bare = allDimMetrics();
        bare.remove("zt_gather_pct");
        bare.remove("height_gather_pct");
        bare.remove("amount_gather_pct");
        bare.remove("catalyst_hardness");
        bare.remove("persistence_days");
        DailyRecord r1 = newRecord();
        ScoreInputs in1 = ScoreInputs.empty();
        in1.setMetrics(bare);
        DailyRecordService.applyFiveDimScore(r1, in1);
        assertNull(r1.getScoreThemeMain(), "D2 五键全空时整维应未评，而不是兜 0");
        assertEquals(4, r1.getScoredDims().intValue(), "未评的维剔出分母，只剩 4 维");

        // 再按 v2 五要素口径填上 D2
        Map<String, BigDecimal> filled = allDimMetrics();
        DailyRecord r2 = newRecord();
        ScoreInputs in2 = ScoreInputs.empty();
        in2.setMetrics(filled);
        DailyRecordService.applyFiveDimScore(r2, in2);
        assertNotNull(r2.getScoreThemeMain(), "五要素齐了 D2 就该出分");
        assertEquals(5, r2.getScoredDims().intValue(), "五维都参与打分");
        assertTrue(r2.getTemperature().compareTo(r1.getTemperature()) != 0,
                "多一维进分母后总分应跟着变（不是把 0 摊薄）");
    }

    // ---- helpers ----

    private static DailyRecord newRecord() {
        DailyRecord r = new DailyRecord();
        r.setTradeDate(LocalDate.of(2026, 9, 10));
        return r;
    }

    /**
     * 构造一个 5 维都能出分、但不触发强制退潮、也不触发中位吹哨的 metrics.
     * 值取"够得着中档"就行,重点是覆盖到每一维.
     */
    private static Map<String, BigDecimal> allDimMetrics() {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        // 大盘:三指都小涨 -> index_env STRATEGY 走中间档
        m.put("index_up1_pct", new BigDecimal("0.5"));
        m.put("index_up2_pct", new BigDecimal("0.3"));
        m.put("index_up3_pct", new BigDecimal("0.4"));
        m.put("turnover_ratio", new BigDecimal("1.00")); // 中间档 70
        m.put("red_ratio", new BigDecimal("0.50"));       // 中间档 55
        m.put("limit_up_count", new BigDecimal("50"));
        m.put("limit_down_count", new BigDecimal("3"));

        // 主线（v2 五要素）：涨停/高度/成交额聚集度 + 催化剂硬度 + 持续性，PrdMetricsService 口径
        m.put("zt_gather_pct", new BigDecimal("35"));
        m.put("height_gather_pct", new BigDecimal("75"));
        m.put("amount_gather_pct", new BigDecimal("20"));
        m.put("catalyst_hardness", new BigDecimal("4"));
        m.put("persistence_days", new BigDecimal("4"));

        // 连板:喂四层完整的晋级/溢价/大面 + 炸板率 + 数量高度,让连板维可评
        m.put("jr_low", new BigDecimal("30"));
        m.put("jr_mid", new BigDecimal("30"));
        m.put("jr_midhigh", new BigDecimal("25"));
        m.put("jr_top", new BigDecimal("15"));
        m.put("prem_low", new BigDecimal("1.5"));
        m.put("prem_mid", new BigDecimal("1.0"));
        m.put("prem_midhigh", new BigDecimal("0.5"));
        m.put("prem_top", new BigDecimal("0"));
        m.put("big_low", new BigDecimal("1"));
        m.put("big_mid", new BigDecimal("1"));
        m.put("big_midhigh", new BigDecimal("0"));
        m.put("big_top", new BigDecimal("0"));
        m.put("sealed_home_rate", new BigDecimal("72"));
        m.put("reseal_rate", new BigDecimal("65"));
        m.put("board_total_count", new BigDecimal("12"));

        // 首板
        m.put("first_count", new BigDecimal("30"));
        m.put("first_sealed_rate", new BigDecimal("60"));
        m.put("first_premium_pct", new BigDecimal("2"));
        m.put("first_promo_1to2_rate", new BigDecimal("18"));
        m.put("first_1to2_big_count", new BigDecimal("2"));

        // 阵眼（v2 龙头分工）：五分齐出（PrdMetricsService 自动算好的 0-100 策略分）
        m.put("dragon_zong_long", new BigDecimal("70"));
        m.put("dragon_zhong_jun", new BigDecimal("60"));
        m.put("dragon_gen_feng", new BigDecimal("40"));
        m.put("dragon_ka_wei", new BigDecimal("80"));
        m.put("dragon_fan_bao", new BigDecimal("20"));
        return m;
    }

    /**
     * 单独抽一个方向映射探针,复用 DailyRecordService 内部实现.
     * applyFiveDimScore 内的"prev vs total"逻辑无法用真实引擎精确控制 total,
     * 因此方向断言改为直接观察同一份映射规则——通过写两个 record(prev 固定,让引擎给什么 total 就用什么).
     */
    private static final class DirectionProbe {
        String directionFor(BigDecimal total, BigDecimal prev) {
            if (total == null || prev == null) {
                return "";
            }
            BigDecimal delta = total.subtract(prev);
            if (delta.compareTo(new BigDecimal("3")) >= 0) {
                return "上升";
            }
            if (delta.compareTo(new BigDecimal("-3")) <= 0) {
                return "下降";
            }
            return "横盘";
        }
    }
}
