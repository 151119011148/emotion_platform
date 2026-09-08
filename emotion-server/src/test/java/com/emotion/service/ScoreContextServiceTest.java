package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.emotion.entity.DailyRecord;
import com.emotion.util.ScoreInputs;
import com.emotion.util.TemperatureCalculator;
import com.emotion.vo.AnchorVO;
import com.emotion.vo.SurveillanceVO;

/**
 * 依据串的组装。这两串文字是落进 {@code t_daily_record} 的唯一解释，
 * 界面上"这个 0 分哪来的"全靠它，所以取哪一只、少了几只票时必须说什么，都在这儿断言。
 *
 * <p>另一半是 {@code applyManual}：人工覆盖列全空时必须等于这一层根本没跑过。
 * "没填"和"填了 0"是两个不同的结论，这里差一个字，{@code recalcAll} 全量跑的那天就会把所有历史温度一起换掉。
 */
class ScoreContextServiceTest {

    private static AnchorVO.Item anchor(String name, Integer score, String reason) {
        AnchorVO.Item item = new AnchorVO.Item();
        item.setName(name);
        item.setScore(score);
        item.setReason(reason);
        return item;
    }

    /** 进分的是最差那只，依据串就只能出现那只的话——把收红那只写进去是误导。 */
    @Test
    void anchorNoteQuotesTheStockThatActuallyScored() {
        AnchorVO vo = new AnchorVO();
        vo.setScore(0);
        vo.setNote("在位 2 只：进分取最差 0 分（阵眼是哨兵，不取平均）");
        vo.getItems().add(anchor("哈药股份", 0, "哈药股份 盘中触板 -7.47%（最低 -9.96%） · 跨度第 40 日"));
        vo.getItems().add(anchor("龙版传媒", 3, "龙版传媒 收红·创跨度新高 9.97% · 跨度第 8 日"));

        String note = ScoreContextService.anchorNote(vo);

        assertTrue(note.contains("盘中触板"));
        assertFalse(note.contains("创跨度新高"));
        assertTrue(note.startsWith("0 分｜"));
    }

    /** 多只并列最差要说全，只报第一只等于把另一只的跌停藏起来。 */
    @Test
    void anchorNoteListsEveryStockTiedAtTheWorst() {
        AnchorVO vo = new AnchorVO();
        vo.setScore(0);
        vo.setNote("在位 2 只：进分取最差 0 分");
        vo.getItems().add(anchor("哈药股份", 0, "哈药股份 收盘跌停 -10.02%"));
        vo.getItems().add(anchor("湖南黄金", 0, "湖南黄金 断板 +3.10%"));

        String note = ScoreContextService.anchorNote(vo);

        assertTrue(note.contains("收盘跌停"));
        assertTrue(note.contains("断板"));
    }

    /** 第 8 维未评时没有"进分那只"，串就退化成状态说明，不能留空。 */
    @Test
    void anchorNoteFallsBackToTheSummaryWhenNothingScored() {
        AnchorVO vo = new AnchorVO();
        vo.setScore(null);
        vo.setAvailable(false);
        vo.setNote("未设阵眼：第 8 维不计入分母（不是 0 分）");

        assertEquals("未设阵眼：第 8 维不计入分母（不是 0 分）", ScoreContextService.anchorNote(vo));
    }

    /** 均值算式后面要能追到"这是谁给的"：只有进分的那只配出现，例行异动熬得再久也不在这句话里。 */
    @Test
    void survivalNoteAppendsTheLongestRunningStock() {
        SurveillanceVO vo = new SurveillanceVO();
        vo.setCount(2);
        vo.setAllCount(9);
        vo.setMatched(2);
        vo.setAvgPct(new BigDecimal("-3.20"));
        vo.setNote("监管股今日溢价 = -3.20% = 2 只涨幅均值（进分 2 家：严重异常波动/交易所监管）；"
                + "另有 7 只例行异常波动，只展示不进分");
        SurveillanceVO.Item longest = new SurveillanceVO.Item();
        longest.setName("哈药股份");
        longest.setScored(true);
        longest.setDescribe("严重异常波动 08/21 第9/10日");
        vo.getItems().add(longest);

        String note = ScoreContextService.survNote(vo);

        assertTrue(note.startsWith("监管股今日溢价"));
        assertTrue(note.endsWith("剩余最久 哈药股份 严重异常波动 08/21 第9/10日"));
    }

    /** 名单上全是例行异动、一只都不进分：后面那一截不存在，不许把 ZD 那只说成"剩余最久"。 */
    @Test
    void survivalNoteStandsAloneWhenNobodyIsListed() {
        SurveillanceVO vo = new SurveillanceVO();
        vo.setNote("当日无严重异常波动/交易所监管在列（第 9 维不计入分母，不是 0 分）；"
                + "另有 8 只例行异常波动，只展示不进分");
        SurveillanceVO.Item routine = new SurveillanceVO.Item();
        routine.setName("某只例行异动");
        routine.setScored(false);
        routine.setDescribe("异常波动 09/02 第1/3日");
        vo.getItems().add(routine);

        assertEquals("当日无严重异常波动/交易所监管在列（第 9 维不计入分母，不是 0 分）；"
                + "另有 8 只例行异常波动，只展示不进分", ScoreContextService.survNote(vo));
    }

    /** 一只都不在位、连状态说明都没给：返回 null，不能拼出一个字面量 "null" 显示在卡片上。 */
    @Test
    void absentAnchorNoteStaysNullRatherThanTheStringNull() {
        AnchorVO vo = new AnchorVO();
        vo.setScore(null);
        vo.setNote(null);
        vo.setItems(new java.util.ArrayList<AnchorVO.Item>());

        assertNull(ScoreContextService.anchorNote(vo));
    }

    // ---------- 人工覆盖：叠在第 8/9 维的公开读数上 ----------

    /** 一天刚取齐的公开读数：第 8 维 2 分、第 9 维 3 家均值 -1.20%、盘面明细是 09-04 那组家数。 */
    private static ScoreInputs publicReadings() {
        com.emotion.market.PoolCounts counts = new com.emotion.market.PoolCounts();
        counts.setZtCount(39);
        counts.setZbCount(48);
        counts.setResealCount(21);
        ScoreInputs in = ScoreInputs.empty();
        in.setPoolCounts(counts);
        in.setAnchorScore(2);
        in.setAnchorNote("2 分｜哈药股份 收红·创跨度新高 9.97% · 跨度第 8 日");
        in.setSurvCount(3);
        in.setSurvPremium(new BigDecimal("-1.20"));
        in.setSurvNote("监管股今日溢价 = -1.20% = 3 只涨幅均值");
        return in;
    }

    /**
     * 八列全 NULL 是绝大多数日子的常态，不是边界情况：{@code recalcAll} 全量跑的就是这个常态。
     * 这里锁"没填就一个字节都不动"，真实日子的逐列基线在 RecalcGoldenTest。
     */
    @Test
    void untouchedWhenEveryManualCellIsEmpty() {
        ScoreInputs blank = publicReadings();
        ScoreInputs filled = publicReadings();

        ScoreContextService.applyManual(filled, new DailyRecord());
        ScoreContextService.applyManual(blank, null);

        assertEquals(2, filled.getAnchorScore().intValue());
        assertEquals("2 分｜哈药股份 收红·创跨度新高 9.97% · 跨度第 8 日", filled.getAnchorNote());
        assertEquals("监管股今日溢价 = -1.20% = 3 只涨幅均值", filled.getSurvNote());
        assertEquals(0, new BigDecimal("-1.20").compareTo(filled.getSurvPremium()));
        assertEquals(blank.getAnchorScore(), filled.getAnchorScore());
    }

    /** 第 2/4 维的人工值刻意不在这里叠（它们叠在算分那一层），所以这层不能顺手把它们读进去。 */
    @Test
    void dimTwoAndFourOverridesAreNotAppliedHere() {
        com.emotion.entity.DailyRecord record = new DailyRecord();
        record.setManualSealedHomeRate(new BigDecimal("90"));
        record.setManualPremiumHighPct(new BigDecimal("5.00"));
        ScoreInputs in = publicReadings();

        ScoreContextService.applyManual(in, record);

        assertEquals(2, in.getAnchorScore().intValue());
        assertEquals(3, in.getSurvCount().intValue());
        // 90% 这个人工读数不许倒推成假家数：第 4 维在算分那一层各自 coalesce，这里连池子都不该碰
        assertEquals(39, in.getPoolCounts().getZtCount());
        assertEquals(48, in.getPoolCounts().getZbCount());
    }

    /** 人工值优先，但自动那串不能消失：它是"我改的这个数和盘面差多少"的唯一凭据。 */
    @Test
    void manualAnchorScoreLeadsAndTheAutoTraceFollows() {
        com.emotion.entity.DailyRecord record = new DailyRecord();
        record.setManualAnchorScore(0);
        ScoreInputs in = publicReadings();

        ScoreContextService.applyManual(in, record);

        assertEquals(0, in.getAnchorScore().intValue());
        assertTrue(in.getAnchorNote().startsWith("0 分｜人工覆盖｜自动值来路：2 分｜"), in.getAnchorNote());
    }

    /** 第 8 维的分是唯一直接进分子的覆盖值：越界要夹回来，而且要当场说夹过，不能悄悄改。 */
    @Test
    void outOfBandManualAnchorScoreIsClampedAndAnnounced() {
        com.emotion.entity.DailyRecord record = new DailyRecord();
        record.setManualAnchorScore(9);
        ScoreInputs in = publicReadings();

        ScoreContextService.applyManual(in, record);

        assertEquals(3, in.getAnchorScore().intValue());
        assertTrue(in.getAnchorNote().contains("9 越界，已夹到 3"), in.getAnchorNote());
    }

    /** 只改溢价、家数仍按盘面：两个子值各走各的，进分判据按合起来的那一组重算。 */
    @Test
    void manualSurvPremiumKeepsTheAutoCount() {
        com.emotion.entity.DailyRecord record = new DailyRecord();
        record.setManualSurvPremium(new BigDecimal("-8.00"));
        ScoreInputs in = publicReadings();

        ScoreContextService.applyManual(in, record);

        assertEquals(3, in.getSurvCount().intValue());
        assertEquals(0, new BigDecimal("-8.00").compareTo(in.getSurvPremium()));
        assertTrue(TemperatureCalculator.survivalScored(in.getSurvCount(), in.getSurvPremium()));
        assertTrue(in.getSurvNote().startsWith("第 9 维人工覆盖：进分 3 家，今日溢价 -8.00%"), in.getSurvNote());
    }

    /**
     * 这一格最容易踩的坑：把溢价手改成一个刺眼的负数，盘面家数却是 0（那天没有票进分）。
     * 两格都不动它自己没错，凑起来仍然不该出分——串必须当场说"这一维不进分"，
     * 否则卡片上一个 -1 分挂着，读的人会以为 0 家算出了暴跌。
     */
    @Test
    void premiumWithoutAnyStockSaysSoRatherThanScoring() {
        com.emotion.entity.DailyRecord record = new DailyRecord();
        record.setManualSurvPremium(new BigDecimal("-8.00"));
        ScoreInputs in = publicReadings();
        in.setSurvCount(0);

        ScoreContextService.applyManual(in, record);

        assertFalse(TemperatureCalculator.survivalScored(in.getSurvCount(), in.getSurvPremium()));
        assertTrue(in.getSurvNote().contains("家数与溢价缺一（或家数为 0）→ 这一维不进分"), in.getSurvNote());
    }

    /** 只填家数、盘面那天根本没拉过（溢价 null）：同样不进分，但要说"溢价未填"而不是显示成 0%。 */
    @Test
    void countWithoutPremiumReportsWhichOneIsMissing() {
        com.emotion.entity.DailyRecord record = new DailyRecord();
        record.setManualSurvCount(5);
        ScoreInputs in = publicReadings();
        in.setSurvPremium(null);

        ScoreContextService.applyManual(in, record);

        assertEquals(5, in.getSurvCount().intValue());
        assertTrue(in.getSurvNote().startsWith("第 9 维人工覆盖：进分 5 家，今日溢价 未填"), in.getSurvNote());
    }

    /** 两条依据串都要落库（VARCHAR(300) / VARCHAR(500)），加了"人工"这截以后仍然不能顶穿列宽。 */
    @Test
    void manualPrefixKeepsBothNotesInsideTheirColumn() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longText.append("例行异常波动只展示不进分 · ");
        }
        com.emotion.entity.DailyRecord record = new DailyRecord();
        record.setManualAnchorScore(1);
        record.setManualSurvCount(2);
        ScoreInputs in = publicReadings();
        in.setAnchorNote(longText.toString());
        in.setSurvNote(longText.toString());

        ScoreContextService.applyManual(in, record);

        assertEquals(295, in.getAnchorNote().length());
        assertEquals(495, in.getSurvNote().length());
        assertTrue(in.getAnchorNote().startsWith("1 分｜人工覆盖"));
    }
}
