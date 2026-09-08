package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emotion.entity.DailyRecord;
import com.emotion.entity.IndexClose;
import com.emotion.entity.Position;
import com.emotion.entity.Prediction;
import com.emotion.util.ReviewDoc;
import com.emotion.util.ReviewImportParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * md 那一行到库里某一列的映射。这个类错一个字段，库里就是一格错数据，而且界面上看不出来——
 * 所以它必须在落库之前被测干净。全部走静态方法，不碰 Spring、不碰库。
 */
class ReviewImportWriterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 3);

    /** 和解析器单测共用那一份 9/3 手记：模板改了两边会一起红，这正是想要的耦合。 */
    private static final String GOLDEN_NOTE = fixture("/import/review_2026-09-03.md");

    private static ReviewDoc docOf(String... keys) {
        StringBuilder sb = new StringBuilder("```meta\n");
        for (String k : keys) {
            sb.append(k).append('\n');
        }
        return ReviewImportParser.parse(sb.append("```\n").toString(), DAY);
    }

    /** 一格都填过的历史行：用来验"键没写 = 一个字都不动"。 */
    private static DailyRecord populated() {
        DailyRecord r = new DailyRecord();
        r.setMainTheme("液冷服务器");
        r.setScoreTheme(3);
        r.setLeadingStock("国芳集团");
        r.setLeadingStockStatus("加速");
        r.setMidCapStock("大位科技");
        r.setRotationNote("旧观察");
        r.setTomorrowPlan("旧计划");
        r.setMyPositionPct(new BigDecimal("55"));
        r.setUpCount(1846);
        r.setDownCount(3570);
        r.setCompareNote("旧对照");
        // 系统自己取的那七个：导入器没有一份映射碰它们
        r.setMaxConsecutiveLimit(5);
        r.setLimitUpCount(44);
        r.setLimitDownCount(16);
        r.setBrokenBoardRate(new BigDecimal("28.4"));
        r.setBigLossCount(7);
        r.setTotalVolume(new BigDecimal("17589"));
        r.setYesterdayLimitPremium(new BigDecimal("-1.09"));
        return r;
    }

    private static DailyRecord apply(ReviewDoc doc) {
        DailyRecord target = populated();
        ReviewImportWriter.applySingles(target, doc, Collections.<String, String>emptyMap());
        return target;
    }

    /** 他 9/3 那份手记一路到列名上的落点。语法过了但读错列，比导不进去更难发现。 */
    @Test
    void goldenNoteLandsOnTheColumnsItIsSupposedTo() {
        DailyRecord target = populated();
        ReviewImportWriter.applySingles(target, ReviewImportParser.parse(GOLDEN_NOTE, DAY),
                Collections.<String, String>emptyMap());

        assertEquals("液冷服务器", target.getMainTheme());
        assertEquals(Integer.valueOf(1), target.getScoreTheme());
        assertEquals("国芳集团", target.getLeadingStock());
        assertEquals("加速", target.getLeadingStockStatus());
        // 中军: 写了空 = 明确要求清掉，键没写的那些（比如轮动观察以外）才保持原样
        assertEquals("", target.getMidCapStock());
        assertTrue(target.getRotationNote().startsWith("军工9/2"));
        assertEquals(new BigDecimal("5"), target.getMyPositionPct());
        assertEquals(Integer.valueOf(1846), target.getUpCount());
        assertEquals(Integer.valueOf(3570), target.getDownCount());
        assertEquals(Integer.valueOf(44), target.getLimitUpCount());
        assertTrue(target.getCompareNote().startsWith("手记 46涨停/17跌停"), target.getCompareNote());
    }

    @Test
    void keysThatAreNotWrittenLeaveTheirColumnsAlone() {
        DailyRecord after = apply(docOf("date: 2026-09-03"));

        assertEquals("液冷服务器", after.getMainTheme());
        assertEquals(Integer.valueOf(3), after.getScoreTheme());
        assertEquals("国芳集团", after.getLeadingStock());
        assertEquals(new BigDecimal("55"), after.getMyPositionPct());
        assertEquals(Integer.valueOf(1846), after.getUpCount());
        assertEquals("旧对照", after.getCompareNote());
    }

    /**
     * 对照是文本列里唯一的例外：它挂了 ALWAYS（复盘页那一格要能清空回"没填"），
     * 所以 blank 落 NULL 而不是 {@code main_theme} 那种空串。写反了的症状是
     * "删掉重进，那行字又回来了"——读取路径会把 NULL 兜底成原文里那份。
     */
    @Test
    void compareNoteClearsToNullBecauseItsColumnIsAlwaysStrategy() {
        DailyRecord after = apply(docOf("date: 2026-09-03", "对照:"));

        assertNull(after.getCompareNote());
    }

    /** 七个行情字段和派生列压根不在映射里：这份表只有市场和打分两侧写得起。 */
    @Test
    void marketNumbersAndDerivedColumnsAreOutOfReach() {
        DailyRecord after = apply(docOf("date: 2026-09-03", "主线: 机器人"));

        assertEquals("机器人", after.getMainTheme());
        assertEquals(Integer.valueOf(44), after.getLimitUpCount());
        assertEquals(Integer.valueOf(16), after.getLimitDownCount());
        assertEquals(Integer.valueOf(5), after.getMaxConsecutiveLimit());
        assertEquals(new BigDecimal("28.4"), after.getBrokenBoardRate());
        assertEquals(new BigDecimal("17589"), after.getTotalVolume());
        assertEquals(new BigDecimal("-1.09"), after.getYesterdayLimitPremium());
        assertEquals(Integer.valueOf(7), after.getBigLossCount());
    }

    /**
     * 文本列的"清空"必须落成空串而不是 null：{@code main_theme} 没挂 ALWAYS 策略，
     * MyBatis-Plus 默认的 NOT_NULL 更新策略会把 null 整列跳过——写 null 等于没清。
     */
    @Test
    void blankTextKeyClearsToEmptyStringBecauseNullWouldBeSkippedOnUpdate() {
        DailyRecord after = apply(docOf("date: 2026-09-03", "主线:"));

        assertEquals("", after.getMainTheme());
    }

    /** 数字列正相反：那几列挂了 ALWAYS，清空要写 NULL。0 分是一个读数，不是"没判断"。 */
    @Test
    void blankNumericKeysClearToNull() {
        DailyRecord after = apply(docOf("date: 2026-09-03", "主线明确度:", "我的仓位:", "涨跌家数:"));

        assertNull(after.getScoreTheme());
        assertNull(after.getMyPositionPct());
        assertNull(after.getUpCount());
        assertNull(after.getDownCount());
    }

    @Test
    void breadthPairSplitsAndToleratesSpacesAroundTheSlash() {
        DailyRecord after = apply(docOf("date: 2026-09-03", "涨跌家数: 1846 / 3570"));

        assertEquals(Integer.valueOf(1846), after.getUpCount());
        assertEquals(Integer.valueOf(3570), after.getDownCount());
    }

    /** 库里那列存名字，所以 md 的「代码 名称」只留名称；名字以代码表为准，md 里的旧写法靠边。 */
    @Test
    void leaderAndMidCapStoreTheNameAndTheCodeTableOutranksTheNote() {
        Map<String, String> names = new HashMap<>();
        names.put("601086", "国芳集团");
        names.put("600589", "大位科技");
        DailyRecord target = populated();
        target.setLeadingStock("");
        target.setMidCapStock("");

        ReviewImportWriter.applySingles(target,
                docOf("date: 2026-09-03", "总龙头: 601086 国芳", "中军: 600589 大位科技"), names);

        assertEquals("国芳集团", target.getLeadingStock());
        assertEquals("大位科技", target.getMidCapStock());
    }

    @Test
    void positionRowsTakeTheirNamesFromTheCodeTableAndKeepTheHandWrittenFloat() {
        ReviewDoc doc = docOf("date: 2026-09-03",
                "持仓: 002229 鸿博股份 成本11.17 现价12.12 浮动+8.5 动作未动 应做竞价清仓 纪律违约");
        Map<String, String> names = new HashMap<>();
        names.put("002229", "鸿博股份");

        List<Position> rows = ReviewImportWriter.positionRows(2L, DAY, doc, names);

        assertEquals(1, rows.size());
        Position p = rows.get(0);
        assertEquals("002229", p.getStockCode());
        assertEquals("鸿博股份", p.getStockName());
        assertEquals(new BigDecimal("11.17"), p.getCostPrice());
        // 浮动盈亏% 是你手记的，不由成本/现价反推
        assertEquals(new BigDecimal("8.5"), p.getFloatPct());
        assertEquals("违约", p.getDiscipline());
        assertEquals(Long.valueOf(2L), p.getUserId());
        assertEquals(DAY, p.getTradeDate());
    }

    /** 没写 持仓 = 这次没说，返回 null 让 Store 整块跳过；返回空表反而是"把那天清仓"。 */
    @Test
    void absentRepeatableKeysReturnNullSoTheStoreSkipsThem() {
        ReviewDoc doc = docOf("date: 2026-09-03");

        assertNull(ReviewImportWriter.positionRows(2L, DAY, doc, Collections.<String, String>emptyMap()));
        assertNull(ReviewImportWriter.indexRows(DAY, doc));
        assertTrue(ReviewImportWriter.predictionRows(2L, DAY, doc).isEmpty());
    }

    @Test
    void plansAndAnswersLandOnTheSameDayUnderDifferentKinds() {
        ReviewDoc doc = docOf("date: 2026-09-03",
                "预判: 退潮延续 概率55 条件 竞业达低开低走",
                "预判: 反包修复 概率45",
                "对答案: 路径二 命中 跌停扩至17家");

        List<Prediction> rows = ReviewImportWriter.predictionRows(2L, DAY, doc);

        assertEquals(3, rows.size());
        assertEquals(Prediction.KIND_PLAN, rows.get(0).getKind());
        assertEquals(Integer.valueOf(55), rows.get(0).getProb());
        assertEquals("竞业达低开低走", rows.get(0).getConditionText());
        // 只写概率不写条件 = 没有条件，NULL 而不是空串
        assertNull(rows.get(1).getConditionText());
        assertEquals(Prediction.KIND_ANSWER, rows.get(2).getKind());
        assertEquals("命中", rows.get(2).getResult());
        assertNull(rows.get(2).getProb());
    }

    @Test
    void indexRowsKeepCloseAndChangeAsWritten() {
        ReviewDoc doc = docOf("date: 2026-09-03", "指数: 000001 上证指数 收盘3942.09 涨跌+0.02");

        List<IndexClose> rows = ReviewImportWriter.indexRows(DAY, doc);

        assertEquals(1, rows.size());
        assertEquals("000001", rows.get(0).getIndexCode());
        assertEquals(new BigDecimal("3942.09"), rows.get(0).getClosePrice());
        assertEquals(new BigDecimal("0.02"), rows.get(0).getChangePct());
    }

    /**
     * 同名的预判和对答案必须共存：{@code t_prediction} 的唯一键是 (user, date, kind, name)，
     * 早上写「路径二 概率50」、晚上回写「路径二 命中」是同一天同一名字的两行。
     * 合成一行的话，重导一次就把预判洗没了。
     */
    @Test
    void sameNameUnderDifferentKindsStaysTwoRows() {
        ReviewDoc doc = docOf("date: 2026-09-03",
                "预判: 路径二 概率50 条件 跌停≥20",
                "对答案: 路径二 命中 跌停扩至17家");

        List<Prediction> rows = ReviewImportWriter.predictionRows(2L, DAY, doc);

        assertEquals(2, rows.size());
        assertEquals(Prediction.KIND_PLAN, rows.get(0).getKind());
        assertEquals(Prediction.KIND_ANSWER, rows.get(1).getKind());
        assertEquals(rows.get(0).getName(), rows.get(1).getName());
    }

    private static String fixture(String path) {
        InputStream in = ReviewImportWriterTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("缺少测试 fixture: " + path);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不出 fixture: " + path, e);
        }
    }
}
