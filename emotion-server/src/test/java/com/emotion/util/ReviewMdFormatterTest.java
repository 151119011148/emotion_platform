package com.emotion.util;

import com.emotion.entity.DailyRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染层：{@link ReviewDoc} → md。核心断言只有一件事——<b>生成出来的东西必须能被自家解析器吃回去</b>，
 * 而且吃回去的那份和进来那份逐字段相同。做不到这一点，导出功能就是每天往库里灌一份坏文件。
 *
 * <p>喂 {@link ReviewMdFormatter#render} 的 doc 尽量先 parse 一份真文件出来，不手搓：
 * 手搓的 doc 能构造出解析器永远不会产生的形状，拿它测往返有一半是在测自己写的假数据。
 * 只有"库里带着脏值"这类降级场景才手工造，因为那正是要防的那一类。
 */
class ReviewMdFormatterTest {

    private static final String GOLDEN = "/import/review_2026-09-03.md";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);

    // ---- 往返 ----

    @Test
    void goldenNoteSurvivesRenderAndReparse() {
        ReviewDoc source = ReviewImportParser.parse(fixture(GOLDEN), TODAY);
        assertFalse(source.hasErrors(), "fixture 本身就该是干净的：" + source.errorsText());

        String md = ReviewMdFormatter.render(source, Collections.<String>emptyList(),
                "## 一、读数与打分\n\n正文");
        ReviewDoc back = ReviewImportParser.parse(md, TODAY);

        assertFalse(back.hasErrors(), "生成的文件导不进去：\n" + back.errorsText() + "\n----\n" + md);
        assertTrue(source.getWarnings().isEmpty(), "干净数据不该触发降级");
        assertSameContent(source, back);
    }

    /** 重复生成要幂等：这个按钮一天会被点好几次，每次都换行就没法用 diff 看自己改了什么。 */
    @Test
    void renderingTwiceIsIdempotent() {
        ReviewDoc source = ReviewImportParser.parse(fixture(GOLDEN), TODAY);
        String first = ReviewMdFormatter.render(source, Collections.<String>emptyList(), "正文");
        String second = ReviewMdFormatter.render(ReviewImportParser.parse(first, TODAY),
                Collections.<String>emptyList(), ReviewMdFormatter.proseOf(first));

        assertEquals(first, second);
    }

    @Test
    void bareDocStillGeneratesAnImportableFile() {
        ReviewDoc doc = new ReviewDoc();
        doc.setDate(LocalDate.of(2026, 9, 4));

        String md = ReviewMdFormatter.render(doc, Arrays.asList("库里为空的键没有写进来"), "");

        ReviewDoc back = ReviewImportParser.parse(md, TODAY);
        assertFalse(back.hasErrors(), "只有 date 的最小文件也该能导入：\n" + back.errorsText());
        assertEquals(LocalDate.of(2026, 9, 4), back.getDate());
        assertTrue(md.contains("# 库里为空的键没有写进来"), "meta 注释行要带 # 落在块内");
        assertFalse(md.contains("持仓:"), "没写的键不该出现，出现就得是值或清空标记");
    }

    @Test
    void blankSingleSurvivesBecauseBlankMeansClear() {
        ReviewDoc source = ReviewImportParser.parse(fixture(GOLDEN), TODAY);
        assertEquals("", source.single("中军").getRaw(), "fixture 里 中军 是「写了键但留空」= 要求清空");

        ReviewDoc back = ReviewImportParser.parse(ReviewMdFormatter.render(source, null, null), TODAY);

        assertTrue(back.getSingles().containsKey("中军"),
                "空值在渲染时丢了的话，重新导入就不会再清空那一列");
        assertEquals("", back.single("中军").getRaw());
    }

    // ---- 降级 ----

    /** 库里存着枚举外的历史脏值时，宁可少一行也不能生成一份导不进去的文件。 */
    @Test
    void unparseableRowIsCommentedOutWithAReason() {
        ReviewDoc doc = new ReviewDoc();
        doc.setDate(LocalDate.of(2026, 9, 4));
        doc.getSingles().put("总龙头", new ReviewDoc.Value("国芳集团", 0));
        doc.getSingles().put("龙头状态", new ReviewDoc.Value("看情况", 0));

        String md = ReviewMdFormatter.render(doc, null, null);
        ReviewDoc back = ReviewImportParser.parse(md, TODAY);

        assertFalse(back.hasErrors(), "坏行必须被盖上，不能留在块里：\n" + back.errorsText());
        assertTrue(md.contains("# 总龙头: 国芳集团"), md);
        assertTrue(md.contains("# 龙头状态: 看情况"), md);
        assertFalse(md.contains("\n总龙头: 国芳集团\n"), "降级过的行不该还以活行存在");
        assertEquals(2, doc.getWarnings().size(), doc.getWarnings().toString());
        assertTrue(doc.getWarnings().get(0).contains("降级成注释"), doc.getWarnings().toString());
    }

    /** 值里撞上下一个标签词会把一段切成两段。这种形状只能降级，不能瞎猜。 */
    @Test
    void labelWordInsideAValueDegradesThatRow() {
        ReviewDoc doc = new ReviewDoc();
        doc.setDate(LocalDate.of(2026, 9, 4));
        doc.getPositions().add(new ReviewDoc.PositionRow(0, "002229", "鸿博股份", null, null, null,
                "打板买入", "止损位 成本 之上出", "遵守"));

        String md = ReviewMdFormatter.render(doc, null, null);
        ReviewDoc back = ReviewImportParser.parse(md, TODAY);

        assertTrue(md.contains("# 持仓: 002229 鸿博股份 动作打板买入 应做止损位 成本 之上出 纪律遵守"),
                "「成本」落在 应做 的值里会被当成标签，这一行只能降级：\n" + md);
        assertFalse(back.hasErrors(), back.errorsText());
        assertTrue(doc.getWarnings().get(0).contains("不是数字"), doc.getWarnings().toString());
    }

    @Test
    void missingDateCannotBeRendered() {
        ReviewDoc doc = new ReviewDoc();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ReviewMdFormatter.render(doc, null, null));
        assertTrue(e.getMessage().contains("date"), e.getMessage());
    }

    // ---- 值清洗与数字形状 ----

    @Test
    void asciiColonAndNewlineAreRewrittenNotDropped() {
        assertEquals("计划： 明天竞价；后天看", ReviewMdFormatter.clean("计划: 明天竞价\n后天看"));
        assertEquals("单行", ReviewMdFormatter.clean("  单行  "));
        assertEquals("两个 空格", ReviewMdFormatter.clean("两个\t空格"));
        assertEquals("", ReviewMdFormatter.clean(null));
    }

    @Test
    void decimalZerosAreStrippedAndPctKeepsItsSign() {
        assertEquals("22", ReviewMdFormatter.number(new BigDecimal("22.00"), false));
        assertEquals("+8.5", ReviewMdFormatter.number(new BigDecimal("8.50"), true));
        assertEquals("-6.7", ReviewMdFormatter.number(new BigDecimal("-6.7"), true));
        // 仓位那一格单位是"成"，正数不该长成 +5 让人以为是涨跌
        assertEquals("5", ReviewMdFormatter.number(new BigDecimal("5.0"), false));
        assertEquals("0", ReviewMdFormatter.number(new BigDecimal("0.00"), true));
    }

    @Test
    void halfBreadthIsNotWrittenAtAll() {
        assertEquals("1846/3570", ReviewMdFormatter.upDown(1846, 3570));
        assertNull(ReviewMdFormatter.upDown(1846, null));
        assertNull(ReviewMdFormatter.upDown(null, 3570));
        assertNull(ReviewMdFormatter.upDown(null, null));
    }

    // ---- 正文 ----

    @Test
    void proseOfStripsOnlyTheMetaBlock() {
        String prose = ReviewMdFormatter.proseOf(fixture(GOLDEN));

        assertFalse(prose.contains("```meta"), "围栏块本身也算块内，不该带出来");
        assertFalse(prose.contains("主线明确度: 1"), "meta 里的键值不该混进正文");
        assertTrue(prose.startsWith("# 每日复盘 · 2026-09-03"), prose);
        assertTrue(prose.contains("一字板高标含金量不足"), "正文末尾要完整：" + prose);
    }

    @Test
    void snapshotIsReplacedInPlaceNotAppended() {
        String body = "# 每日复盘 · 2026-09-04\n\n"
                + ReviewMdFormatter.SNAPSHOT_HEADING + "\n\n旧的一版读数\n\n"
                + "## 一、读数与打分\n\n我写的东西\n";

        String after = ReviewMdFormatter.replaceSnapshot(body,
                ReviewMdFormatter.SNAPSHOT_HEADING + "\n\n新的读数");

        assertEquals(1, count(after, ReviewMdFormatter.SNAPSHOT_HEADING), after);
        assertFalse(after.contains("旧的一版读数"), after);
        assertTrue(after.contains("我写的东西"), "用户手写的正文一个字都不该丢：" + after);
        assertTrue(after.indexOf(ReviewMdFormatter.SNAPSHOT_HEADING) > after.indexOf("# 每日复盘"),
                "文件标题在最上面，快照跟在它后面：\n" + after);
    }

    @Test
    void snapshotGoesRightAfterTheTitleWhenBodyHasNoneYet() {
        String after = ReviewMdFormatter.replaceSnapshot(
                "# 每日复盘 · 2026-09-04\n\n## 一、读数与打分\n\nx",
                ReviewMdFormatter.SNAPSHOT_HEADING + "\n\n读数");

        assertTrue(after.indexOf(ReviewMdFormatter.SNAPSHOT_HEADING) > after.indexOf("# 每日复盘"), after);
        assertTrue(after.contains("## 一、读数与打分"), after);
    }

    @Test
    void snapshotCarriesNineDimsAndRefusesToInventScores() {
        String snap = ReviewMdFormatter.snapshot(record());

        assertTrue(snap.startsWith(ReviewMdFormatter.SNAPSHOT_HEADING), snap);
        assertTrue(snap.contains("温度 55.6° · 阶段 反弹 · 二阶段（升） · 进分 8/9 · 总分 7"), snap);
        assertTrue(snap.contains("| 1 连板高度 | 5 | 1 |"), snap);
        assertTrue(snap.contains("| 2 分档溢价 | +1.41%（昨日涨停 -0.6%） | 0 |"), snap);
        assertTrue(snap.contains("| 3 涨停/跌停 | 39/9 | -1 |"), snap);
        assertTrue(snap.contains("| 4 炸板率 | +84.7% | 0 |"), snap);
        assertTrue(snap.contains("| 6 成交额 | 20306.68亿 | 1 |"), snap);
        assertTrue(snap.contains("| 7 主线明确度 | 有清晰主线 + 龙头 | 3 |"), snap);
        assertTrue(snap.contains("| 8 周期阵眼 | 阵眼 国芳集团 跨度 +2.1% | 2 |"), snap);
        assertTrue(snap.contains("| 9 异动监管 | 4 家 / -1.02% | — |"), snap);
        // 表头那一行也算 "\n| "，所以是 10 而不是 9
        assertEquals(10, count(snap, "\n| "), "九维一行都不该少：\n" + snap);
    }

    @Test
    void unassessedDimsShowAsDashNotZero() {
        DailyRecord r = new DailyRecord();
        r.setMaxConsecutiveLimit(5);

        String snap = ReviewMdFormatter.snapshot(r);

        assertTrue(snap.contains("| 1 连板高度 | 5 | — |"), snap);
        assertTrue(snap.contains("温度 —"), snap);
        assertFalse(snap.contains("| 0 |\n"), "未评的维画成 0 分，等于把缺数据说成一次读盘结论：" + snap);
    }

    @Test
    void snapshotSaysSoWhenTheDayHasNoRecordYet() {
        String snap = ReviewMdFormatter.snapshot(null);

        assertTrue(snap.contains("还没有系统读数"), snap);
        assertFalse(snap.contains("| 1 连板高度"), "没有记录就不该画出一张全是 — 的假表");
    }

    // ---- 夹具 ----

    private static DailyRecord record() {
        DailyRecord r = new DailyRecord();
        r.setMaxConsecutiveLimit(5);
        r.setLimitUpCount(39);
        r.setLimitDownCount(9);
        r.setBrokenBoardRate(new BigDecimal("84.70"));
        r.setBigLossCount(4);
        r.setTotalVolume(new BigDecimal("20306.68"));
        r.setPremiumWeighted(new BigDecimal("1.41"));
        r.setYesterdayLimitPremium(new BigDecimal("-0.60"));
        r.setScoreHeight(1);
        r.setScorePremium(0);
        r.setScoreBreadth(-1);
        r.setScoreBroken(0);
        r.setScoreLoss(1);
        r.setScoreVolume(1);
        r.setScoreTheme(3);
        r.setAnchorNote("阵眼 国芳集团 跨度 +2.1%");
        r.setAnchorScore(2);
        r.setSurvCount(4);
        r.setSurvPremium(new BigDecimal("-1.02"));
        r.setTemperature(new BigDecimal("55.60"));
        r.setScoredDims(8);
        r.setTotalScore(7);
        r.setStage("修复");
        r.setStagePhase("反弹");
        r.setStageSeq(2);
        r.setStageDirection("升");
        return r;
    }

    /**
     * 逐字段比，但<b>不比行号</b>：{@code line} 记的是"这行原来在第几行"，渲染之后必然变了，
     * 拿它比只会把这个测试退化成对排版的快照。
     */
    private static void assertSameContent(ReviewDoc a, ReviewDoc b) {
        assertEquals(a.getDate(), b.getDate(), "date");
        Set<String> keys = a.getSingles().keySet();
        assertEquals(keys, b.getSingles().keySet(), "键集合");
        for (String key : keys) {
            assertEquals(a.single(key).getRaw(), b.single(key).getRaw(), "键 " + key + " 的值");
        }

        assertEquals(a.getPositions().size(), b.getPositions().size(), "持仓行数");
        for (int i = 0; i < a.getPositions().size(); i++) {
            ReviewDoc.PositionRow x = a.getPositions().get(i);
            ReviewDoc.PositionRow y = b.getPositions().get(i);
            assertEquals(x.getCode(), y.getCode(), "持仓代码 第" + i + "行");
            assertEquals(x.getName(), y.getName(), "持仓名称 第" + i + "行");
            same(x.getCost(), y.getCost(), "成本");
            same(x.getCurrent(), y.getCurrent(), "现价");
            same(x.getFloatPct(), y.getFloatPct(), "浮动");
            assertEquals(x.getAction(), y.getAction(), "动作");
            assertEquals(x.getPlannedAction(), y.getPlannedAction(), "应做");
            assertEquals(x.getDiscipline(), y.getDiscipline(), "纪律");
        }

        assertEquals(a.getThemes().size(), b.getThemes().size(), "题材行数");
        for (int i = 0; i < a.getThemes().size(); i++) {
            ReviewDoc.ThemeRow x = a.getThemes().get(i);
            ReviewDoc.ThemeRow y = b.getThemes().get(i);
            assertEquals(x.getTheme(), y.getTheme(), "题材名");
            assertEquals(x.getStrength(), y.getStrength(), "强度");
            assertEquals(x.getStatus(), y.getStatus(), "题材状态");
            assertEquals(x.getLeaderCode(), y.getLeaderCode(), "龙头代码");
            assertEquals(x.getLeaderName(), y.getLeaderName(), "龙头名称");
        }

        assertEquals(a.getPlans().size(), b.getPlans().size(), "预判行数");
        for (int i = 0; i < a.getPlans().size(); i++) {
            ReviewDoc.PlanRow x = a.getPlans().get(i);
            ReviewDoc.PlanRow y = b.getPlans().get(i);
            assertEquals(x.getName(), y.getName(), "路径名");
            assertEquals(x.getProb(), y.getProb(), "概率");
            assertEquals(x.getCondition(), y.getCondition(), "条件");
        }

        assertEquals(a.getAnswers().size(), b.getAnswers().size(), "对答案行数");
        for (int i = 0; i < a.getAnswers().size(); i++) {
            ReviewDoc.AnswerRow x = a.getAnswers().get(i);
            ReviewDoc.AnswerRow y = b.getAnswers().get(i);
            assertEquals(x.getName(), y.getName(), "对答案名称");
            assertEquals(x.getResult(), y.getResult(), "兑现结果");
            assertEquals(x.getNote(), y.getNote(), "对答案依据");
        }

        assertEquals(a.getIndexes().size(), b.getIndexes().size(), "指数行数");
        for (int i = 0; i < a.getIndexes().size(); i++) {
            ReviewDoc.IndexRow x = a.getIndexes().get(i);
            ReviewDoc.IndexRow y = b.getIndexes().get(i);
            assertEquals(x.getCode(), y.getCode(), "指数代码");
            assertEquals(x.getName(), y.getName(), "指数名称");
            same(x.getClose(), y.getClose(), "收盘");
            same(x.getChangePct(), y.getChangePct(), "指数涨跌");
        }
    }

    /** DECIMAL 的 scale 不参与判断：库里 22.00，渲染成 22，读回来还是同一个数。 */
    private static void same(BigDecimal x, BigDecimal y, String what) {
        if (x == null || y == null) {
            assertTrue(x == null && y == null, what + " 一边有一边没有：" + x + " vs " + y);
            return;
        }
        assertEquals(0, x.compareTo(y), what + " 数值变了：" + x + " vs " + y);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static String fixture(String path) {
        try (InputStream in = ReviewMdFormatterTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺测试夹具：" + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不了 " + path, e);
        }
    }
}
