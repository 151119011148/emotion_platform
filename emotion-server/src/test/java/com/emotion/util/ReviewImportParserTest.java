package com.emotion.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复盘 md 解析器。喂的是字符串，不建 Spring 上下文——解析器的全部职责就是把
 * 「你写的这一行能不能读、读成什么」答清楚，剩下的都在 service。
 */
class ReviewImportParserTest {

    /** 那份 9/3 手记的导入块。语法边界都在这一个文件里，它导不进去就是最常见的一天崩了。 */
    private static final String GOLDEN = "/import/review_2026-09-03.md";

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);

    // ---- 正常路径 ----

    @Test
    void goldenNoteParsesWhole() {
        ReviewDoc doc = ReviewImportParser.parse(fixture(GOLDEN), TODAY);

        assertFalse(doc.hasErrors(), "9/3 那份复盘不该有坏行：" + describe(doc));
        assertEquals(LocalDate.of(2026, 9, 3), doc.getDate());
        assertTrue(doc.isProse(), "围栏块之外的正文要能识别出来，review_md 存的就是整篇");

        assertEquals(2, doc.getPositions().size());
        assertEquals(2, doc.getThemes().size());
        assertEquals(3, doc.getPlans().size());
        assertEquals(2, doc.getAnswers().size());
        assertEquals(5, doc.getIndexes().size());
    }

    @Test
    void positionRowKeepsHandRecordedFloatInsteadOfRecomputingIt() {
        ReviewDoc doc = ReviewImportParser.parse(fixture(GOLDEN), TODAY);

        ReviewDoc.PositionRow hongbo = doc.getPositions().get(0);
        assertEquals("002229", hongbo.getCode());
        assertEquals("鸿博股份", hongbo.getName());
        assertEquals(new BigDecimal("11.17"), hongbo.getCost());
        assertEquals(new BigDecimal("12.12"), hongbo.getCurrent());
        // +8.5 是手记值。成本现价反推出来是 +8.505…，你记的可能是含费后的数，别替他重算。
        assertEquals(new BigDecimal("8.5"), hongbo.getFloatPct());
        assertEquals("未动", hongbo.getAction());
        assertEquals("竞价清仓", hongbo.getPlannedAction());
        assertEquals("违约", hongbo.getDiscipline());

        assertEquals(new BigDecimal("-6.7"), doc.getPositions().get(1).getFloatPct());
    }

    @Test
    void labelSegmentsSurviveSpacesInValues() {
        ReviewDoc doc = ReviewImportParser.parse(fixture(GOLDEN), TODAY);

        // "应做次日竞价止损" 是一个带空格的值，边界由下一个标签"纪律"决定
        ReviewDoc.PositionRow jingye = doc.getPositions().get(1);
        assertEquals("次日竞价止损", jingye.getPlannedAction());
        assertEquals("待执行", jingye.getDiscipline());
    }

    @Test
    void planKeepsProbabilityAndWholeConditionTail() {
        ReviewDoc doc = ReviewImportParser.parse(fixture(GOLDEN), TODAY);

        ReviewDoc.PlanRow first = doc.getPlans().get(0);
        assertEquals("弱修复延续", first.getName());
        assertEquals(Integer.valueOf(25), first.getProb());
        assertTrue(first.getCondition().startsWith("竞业达企稳"), first.getCondition());
        assertTrue(first.getCondition().endsWith("成交≥1.9万亿"), first.getCondition());
    }

    @Test
    void indexRowsWithOnlyChangeAreFine() {
        ReviewDoc doc = ReviewImportParser.parse(fixture(GOLDEN), TODAY);

        assertEquals(new BigDecimal("3942.09"), doc.getIndexes().get(0).getClose());
        assertEquals(new BigDecimal("0.02"), doc.getIndexes().get(0).getChangePct());
        // 深成那天只记了涨跌没记收盘：缺的是"没说"，不是 0。
        assertNull(doc.getIndexes().get(1).getClose());
        assertEquals(new BigDecimal("0.10"), doc.getIndexes().get(1).getChangePct());
        assertEquals(new BigDecimal("-1.61"), doc.getIndexes().get(4).getChangePct());
    }

    @Test
    void answerSplitsIntoNameResultNote() {
        ReviewDoc doc = ReviewImportParser.parse(fixture(GOLDEN), TODAY);

        ReviewDoc.AnswerRow path2 = doc.getAnswers().get(0);
        assertEquals("路径二", path2.getName());
        assertEquals("命中", path2.getResult());
        assertEquals("跌停扩至17家+竞业达5板失败", path2.getNote());
        assertEquals("违约", doc.getAnswers().get(1).getResult());
    }

    @Test
    void blankKeyAndMissingKeyAreDifferentThings() {
        // 模板里 "中军:" 就是空值：这天明确没有中军，要清掉上次导进去的那个。
        ReviewDoc doc = ReviewImportParser.parse(fixture(GOLDEN), TODAY);
        assertTrue(doc.single("中军").isBlank(), "写了键、值为空 = 清空");

        ReviewDoc onlyDate = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n```", TODAY);
        assertFalse(onlyDate.hasErrors(), onlyDate.errorsText());
        assertNull(onlyDate.single("中军"), "不写这个键 = 这天没说，库里不动");
    }

    @Test
    void freeTextValuesWithSlashesAndFullWidthPunctuationSurvive() {
        ReviewDoc doc = ReviewImportParser.parse(fixture(GOLDEN), TODAY);

        assertEquals("液冷服务器", doc.single("主线").getRaw());
        assertTrue(doc.single("轮动观察").getRaw().contains("一日游"));
        assertEquals("1846/3570", doc.single("涨跌家数").getRaw());
        assertTrue(doc.single("对照").getRaw().contains("44/16"),
                "对照键只存不解析，用来兑现「46 vs 44 让你核」这句话");
    }

    // ---- 形状与坏行 ----

    @Test
    void missingMetaFenceIsReportedNotGuessed() {
        ReviewDoc doc = ReviewImportParser.parse("# 今天的复盘\n主线: 液冷\n", TODAY);

        assertTrue(doc.hasErrors());
        assertTrue(doc.errorsText().contains("没找到 ```meta 围栏块"), doc.errorsText());
    }

    @Test
    void twoMetaFencesAreRejected() {
        String md = "```meta\ndate: 2026-09-03\n```\n正文\n```meta\ndate: 2026-09-04\n```\n";
        ReviewDoc doc = ReviewImportParser.parse(md, TODAY);

        assertTrue(doc.hasErrors());
        assertTrue(doc.errorsText().contains("第二个"), doc.errorsText());
        assertNull(doc.getDate());
    }

    @Test
    void unclosedFenceIsReportedWithItsLineNumber() {
        String md = "正文\n\n```meta\ndate: 2026-09-03\n主线: 液冷\n";
        ReviewDoc doc = ReviewImportParser.parse(md, TODAY);

        assertTrue(doc.errorsText().contains("没有闭合"), doc.errorsText());
        assertEquals(3, doc.getErrors().get(0).getLine(), "报错行号要和编辑器里数出来的一致");
    }

    @Test
    void commentsAndBlankLinesAreSkippedInsideBlock() {
        String md = "```meta\n\n# 这一行是注释\ndate: 2026-09-03\n主线: 液冷\n```\n";
        ReviewDoc doc = ReviewImportParser.parse(md, TODAY);

        assertFalse(doc.hasErrors(), doc.errorsText());
        assertEquals("液冷", doc.single("主线").getRaw());
    }

    @Test
    void fullWidthColonSeparatorIsAccepted() {
        ReviewDoc doc = ReviewImportParser.parse("```meta\ndate：2026-09-03\n主线：液冷\n```", TODAY);

        assertFalse(doc.hasErrors(), doc.errorsText());
        assertEquals(LocalDate.of(2026, 9, 3), doc.getDate());
        assertEquals("液冷", doc.single("主线").getRaw());
    }

    @Test
    void englishColonInsideValueIsRejected() {
        // 值里有半角冒号会同时踩中"键值分隔"和"分段"两套读法，程序一猜就可能猜错方向。
        ReviewDoc doc = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n主线: 液冷: 服务器\n```", TODAY);

        assertTrue(doc.errorsText().contains("值里有英文冒号"), doc.errorsText());
        assertNull(doc.single("主线"));
    }

    @Test
    void lineWithoutSeparatorIsReported() {
        ReviewDoc doc = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n液冷服务器很强\n```", TODAY);

        assertEquals(1, doc.getErrors().size(), doc.errorsText());
        assertEquals(3, doc.getErrors().get(0).getLine());
        assertTrue(doc.getErrors().get(0).getReason().contains("键: 值"));
    }

    @Test
    void unknownKeyIsRejectedWithTheNearestLegalKey() {
        ReviewDoc doc = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n主线明确: 1\n```", TODAY);

        assertTrue(doc.errorsText().contains("不认识的键"), doc.errorsText());
        assertTrue(doc.errorsText().contains("你是不是想写"), doc.errorsText());
    }

    @Test
    void handWrittenMarketNumbersAreRejectedWithAReason() {
        // 不是洁癖：9/3 手记 46 涨停 / 17 跌停，系统从东财池子取到 44 / 16。两边进同一张表，
        // 事后没人知道该信谁。报"未知键"也不够——他会以为是模板漏了个键，再补一行。
        ReviewDoc doc = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n涨停家数: 46\n跌停家数: 17\n温度: 50\n阶段: 退潮\n```", TODAY);

        assertEquals(4, doc.getErrors().size(), doc.errorsText());
        for (ReviewDoc.ParseError e : doc.getErrors()) {
            assertTrue(e.getReason().contains("自己取") || e.getReason().contains("现算")
                            || e.getReason().contains("产物"),
                    "要说明为什么不收，而不是只说不认识：" + e.describe());
        }
    }

    @Test
    void everyBadLineIsReportedAtOnce() {
        // 整块拒绝 + 一次报全：逐条试错等于让他把同一份文件贴五遍。
        String md = "```meta\ndate: 2026-09-13\n主线明确度: 5\n持仓: 002229 鸿博股份 成本abc 动作未动 应做清仓 纪律违约\n"
                + "对答案: 路径二 应验 命中\n```";
        ReviewDoc doc = ReviewImportParser.parse(md, TODAY);

        assertEquals(4, doc.getErrors().size(), doc.errorsText());
        int previous = -1;
        for (ReviewDoc.ParseError e : doc.getErrors()) {
            assertTrue(e.getLine() >= previous, "错误要按行号排：" + doc.errorsText());
            previous = e.getLine();
        }
    }

    @Test
    void dateLaterThanTodayIsAnErrorButWeekendIsOnlyAWarning() {
        ReviewDoc future = ReviewImportParser.parse("```meta\ndate: 2026-12-31\n```", TODAY);
        assertTrue(future.errorsText().contains("晚于今天"), future.errorsText());

        // 8/29 是周六。写"周末复盘/下周预期"是常态，拦下来只会让人把日期改成周五，那才是污染。
        ReviewDoc weekend = ReviewImportParser.parse("```meta\ndate: 2026-08-29\n```", TODAY);
        assertFalse(weekend.hasErrors(), weekend.errorsText());
        assertTrue(weekend.getWarnings().toString().contains("周末"), weekend.getWarnings().toString());
    }

    @Test
    void invalidEnumValuesAreRejectedListingWhatIsAllowed() {
        ReviewDoc status = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n龙头状态: 起飞\n```", TODAY);
        assertTrue(status.errorsText().contains("加速 / 滞涨 / 断板 / 反包 / 正常"), status.errorsText());

        ReviewDoc theme = ReviewImportParser.parse(
                "```meta\ndate: 2026-09-03\n题材: 液冷 强度70 状态起飞\n```", TODAY);
        assertTrue(theme.errorsText().contains("萌芽 / 确认 / 扩散 / 亢奋 / 退潮"), theme.errorsText());
    }

    @Test
    void answerWithoutKnownResultIsRejected() {
        // 名称对不上的对答案会静默丢统计，所以第二段必须是那四个词之一。
        ReviewDoc doc = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n对答案: 路径二 基本应验 跌停扩至17家\n```", TODAY);

        assertTrue(doc.errorsText().contains("第二段必须是结果"), doc.errorsText());
    }

    @Test
    void positionWithoutRequiredLabelsIsRejectedNamingTheMissingOne() {
        ReviewDoc doc = ReviewImportParser.parse(
                "```meta\ndate: 2026-09-03\n持仓: 002229 鸿博股份 成本11.17 现价12.12\n```", TODAY);

        assertTrue(doc.errorsText().contains("缺标签 动作"), doc.errorsText());
        assertEquals(1, doc.getErrors().size(), doc.errorsText());
    }

    @Test
    void positionWithOptionalPriceLabelsIsAccepted() {
        // 观察仓 / 只写了动作和纪律的那几只，不该被价格必填挡住。
        ReviewDoc doc = ReviewImportParser.parse(
                "```meta\ndate: 2026-09-03\n持仓: 002229 鸿博股份 动作未动 应做竞价清仓 纪律违约\n```", TODAY);

        assertFalse(doc.hasErrors(), doc.errorsText());
        assertNull(doc.getPositions().get(0).getCost());
    }

    @Test
    void stockWithoutSixDigitCodeIsRejected() {
        ReviewDoc doc = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n总龙头: 国芳集团\n```", TODAY);

        assertTrue(doc.errorsText().contains("六位代码"), doc.errorsText());
    }

    @Test
    void duplicateSingleKeyAndDuplicateRowAreBothRejected() {
        ReviewDoc dupKey = ReviewImportParser.parse(
                "```meta\ndate: 2026-09-03\n主线: 液冷\n主线: 军工\n```", TODAY);
        assertTrue(dupKey.errorsText().contains("出现了两次"), dupKey.errorsText());

        ReviewDoc dupRow = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n"
                + "持仓: 002229 鸿博股份 动作未动 应做清仓 纪律违约\n"
                + "持仓: 002229 鸿博股份 动作加仓 应做持有 纪律遵守\n```", TODAY);
        assertTrue(dupRow.errorsText().contains("已经列过一次"), dupRow.errorsText());

        ReviewDoc dupPlan = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n"
                + "预判: 路径二 概率50\n预判: 路径二 概率60\n```", TODAY);
        assertTrue(dupPlan.errorsText().contains("已经有一次"), dupPlan.errorsText());
    }

    @Test
    void labelWordInsidePlainTextIsNotTreatedAsSegmentBoundary() {
        // 「形态」里的"态"不是标签；只有落在 token 边界的"状态"才算。
        ReviewDoc doc = ReviewImportParser.parse(
                "```meta\ndate: 2026-09-03\n题材: 形态修复 强度50 状态确认\n```", TODAY);

        assertFalse(doc.hasErrors(), doc.errorsText());
        assertEquals("形态修复", doc.getThemes().get(0).getTheme());
        assertEquals("确认", doc.getThemes().get(0).getStatus());
    }

    @Test
    void probabilitiesNotAddingToHundredWarnInsteadOfFail() {
        ReviewDoc doc = ReviewImportParser.parse("```meta\ndate: 2026-09-03\n"
                + "预判: 甲 概率40 条件 甲成立\n预判: 乙 概率30 条件 乙成立\n```", TODAY);

        assertFalse(doc.hasErrors(), doc.errorsText());
        assertTrue(doc.getWarnings().toString().contains("预判概率合计 70"), doc.getWarnings().toString());
    }

    @Test
    void lineNumberCountsTheWholeFileNotJustTheBlock() {
        String md = "一\n二\n三\n```meta\ndate: 2026-09-03\n主线明确度: 9\n```\n";
        ReviewDoc doc = ReviewImportParser.parse(md, TODAY);

        assertEquals(1, doc.getErrors().size(), doc.errorsText());
        assertEquals(6, doc.getErrors().get(0).getLine());
        assertEquals("主线明确度", doc.getErrors().get(0).getKey());
    }

    @Test
    void emptyContentIsRejected() {
        ReviewDoc doc = ReviewImportParser.parse("   ", TODAY);

        assertTrue(doc.hasErrors());
        assertNull(doc.getDate());
    }

    // ---- helper ----

    private static String describe(ReviewDoc doc) {
        return doc.errorsText();
    }

    private static String fixture(String path) {
        InputStream in = ReviewImportParserTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("缺少测试 fixture: " + path);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不出 fixture: " + path, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 读都读完了，关掉失败没有意义
            }
        }
    }
}
