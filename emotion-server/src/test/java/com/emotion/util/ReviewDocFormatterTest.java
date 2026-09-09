package com.emotion.util;

import com.emotion.entity.Anchor;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.IndexClose;
import com.emotion.entity.Position;
import com.emotion.entity.Prediction;
import com.emotion.util.ReviewDoc.ThemeRow;
import com.emotion.util.ReviewDocFormatter.Model;
import com.emotion.vo.MarketStocksVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读复盘文档渲染层。这份不承诺可再导入，所以断言的重心不在往返，而在两件事：
 * <b>任何一块数据缺了都不能炸</b>（这按钮就是要在半空的日子上被点），
 * 以及<b>有数据时数字必须按系统口径落进他熟悉的版式</b>（错了就是让他抄错一遍）。
 */
class ReviewDocFormatterTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 4);

    private static final String[] SECTION_HEADINGS = {
            "## 【一、指数与量能】", "## 【二、板块主线", "## 【三、情绪与连板生态】",
            "## 【四、持仓处理评价】", "## 【五、对答案", "## 【六、", "## 【七、关键锚点】",
            "## 【八、仓位与总策略】", "## 【九、免责声明】"
    };

    // ---- 缺数据 ----

    /** 光杆一个 date：九个小节按序齐全、判断占位在、数据处一律 —，一个异常都不许抛。 */
    @Test
    void bareModelRendersEverySectionWithoutThrowing() {
        Model m = new Model();
        m.date = FRIDAY;

        String md = ReviewDocFormatter.render(m);

        for (int i = 0; i < SECTION_HEADINGS.length; i++) {
            assertTrue(md.contains(SECTION_HEADINGS[i]),
                    "缺小节 " + SECTION_HEADINGS[i] + "\n----\n" + md);
            if (i > 0) {
                assertTrue(md.indexOf(SECTION_HEADINGS[i - 1]) < md.indexOf(SECTION_HEADINGS[i]),
                        "小节顺序错了：" + SECTION_HEADINGS[i]);
            }
        }
        assertTrue(md.startsWith("# 周五（9/4）复盘"), md);
        // 九节里除【九】免责声明外，每节都该留一行判断占位
        assertEquals(8, countOf(md, "✍️ 判断"), md);
        // 整天没有读数时给一句说明，比五行各自一个 — 更好读
        assertTrue(md.contains("> 这天还没有系统读数"), md);
        assertTrue(md.contains("- **上证指数**：—"), md);
        assertTrue(md.contains("- **连板梯队**：—"), md);
        assertTrue(md.contains("| 温度(°) | — | — | — |"), md);
        assertTrue(md.contains("| 指标 | 今日 9/4 | 上一记录 | 变化 |"), md);
        assertTrue(md.contains("- **我的仓位 / 明日计划**：—"), md);
    }

    /** 列表字段被服务层置 null 也不能炸——装配方不该需要记得给每个集合兜空。 */
    @Test
    void nullListsAreTolerated() {
        Model m = new Model();
        m.date = FRIDAY;
        m.indexes = null;
        m.positions = null;
        m.predictions = null;
        m.anchors = null;
        m.themes = null;

        assertTrue(ReviewDocFormatter.render(m).contains("【九、免责声明】"));
    }

    @Test
    void missingDateIsTheOnlyHardFailure() {
        assertThrows(IllegalArgumentException.class, () -> ReviewDocFormatter.render(new Model()));
        assertThrows(IllegalArgumentException.class, () -> ReviewDocFormatter.render(null));
    }

    // ---- 系统取数 ----

    @Test
    void recordNumbersLandInTheirSections() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("退潮", 2, "22.00", "1580.50", 1846, 2900, 22, 45, 4);
        m.today.setMainTheme("液冷服务器");
        m.today.setLeadingStock("集泰股份");
        m.today.setLeadingStockStatus("晋级失败");
        m.today.setScoreHeight(3);
        m.indexes = Arrays.asList(
                index("000001", "3842.09", "-0.72"),
                index("399006", "1521.40", "0"));

        String md = ReviewDocFormatter.render(m);

        assertTrue(md.contains("- **上证指数**：3842.09（-0.72%）"), md);
        // 平盘不补正号：给 0 挂个 + 是假信号
        assertTrue(md.contains("- **创业板指**：1521.4（0%）"), md);
        assertTrue(md.contains("- **深证成指**：—"), md);
        assertTrue(md.contains("- **全市场成交**：1580.5 亿"), md);
        assertTrue(md.contains("- **上涨/下跌**：1846 / 2900（上涨占比 39%）"), md);
        assertTrue(md.contains("- **涨停/跌停**：22 / 45"), md);
        assertTrue(md.contains("- **连板高度**：4 板"), md);
        assertTrue(md.contains("温度 22° · 阶段 退潮 · 二阶段 · 总分 —"), md);
        assertTrue(md.contains("- **主线**：液冷服务器"), md);
        assertTrue(md.contains("- **总龙头**：集泰股份 · 晋级失败"), md);
        // 九维是这份模型的骨架，只在仪表盘上有、导出文档里没有，等于把最值钱的一栏丢了
        assertTrue(section(md, "【三、", "【四、").contains("| 1 连板高度 | 4 | 3 |"), md);
        assertTrue(section(md, "【三、", "【四、").contains("第 9 维的分不单列存储"), md);
    }

    /** 已人工改判必须在标题上留痕，否则这份 md 会和仪表盘对不上而看不出为什么。 */
    @Test
    void overriddenStageIsMarked() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("分歧", 1, "44.00", "1580.50", 2600, 2100, 40, 12, 5);
        m.today.setStageOverridden(1);

        assertTrue(ReviewDocFormatter.render(m).startsWith("# 周五（9/4）复盘 · 分歧 · 一阶段（已人工改判）"));
    }

    /** DECIMAL 列带着补出来的 0：22.00 得写成 22，否则整篇读起来像坏数据。 */
    @Test
    void numberFormattingFollowsTheImportContract() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("修复", 1, "33.30", "1580.50", 3300, 1700, 60, 3, 5);
        m.today.setTotalScore(9);
        m.today.setScoredDims(9);
        m.today.setMyPositionPct(new BigDecimal("30.00"));
        m.today.setTomorrowPlan("反弹不参与，等 5 板断位");

        String md = ReviewDocFormatter.render(m);

        assertTrue(md.contains("- **全市场成交**：1580.5 亿"), md);
        assertTrue(md.contains("（上涨占比 66%）"), md);
        assertTrue(md.contains("| 涨停家数 | 60 | — | — |"), md);
        assertTrue(md.contains("总分 9 · 进分 9/9 维"), md);
        assertTrue(md.contains("- **我的仓位**：30 成"), md);
        assertTrue(md.contains("- **明日计划**：反弹不参与，等 5 板断位"), md);
    }

    // ---- 今昨对照 ----

    @Test
    void compareTableShowsDirectionAgainstPreviousDay() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("退潮", 2, "22.00", "1580.50", 1846, 2900, 22, 45, 4);
        m.prev = record("退潮", 1, "33.30", "1600.00", 2400, 2300, 30, 20, 5);
        m.prev.setTradeDate(LocalDate.of(2026, 9, 3));

        String md = ReviewDocFormatter.render(m);

        // 表头带日期：上一记录不等于昨天，不能让读者以为在跟昨天比
        assertTrue(md.contains("| 指标 | 今日 9/4 | 上一记录 9/3 | 变化 |"), md);
        assertTrue(md.contains("| 温度(°) | 22.0 | 33.3 | -11.3 |"), md);
        assertTrue(md.contains("| 成交额(亿) | 1580.50 | 1600.00 | -19.50 |"), md);
        assertTrue(md.contains("| 涨停家数 | 22 | 30 | -8 |"), md);
        assertTrue(md.contains("| 跌停家数 | 45 | 20 | +25 |"), md);
        assertTrue(md.contains("| 上涨家数 | 1846 | 2400 | -554 |"), md);
        assertTrue(md.contains("| 连板高度 | 4 | 5 | -1 |"), md);
    }

    /** 持平要写成「持平」而不是 0 或 +0：对照表是扫着看的，一个裸 0 读不出方向。 */
    @Test
    void unchangedMetricReadsAsFlat() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("退潮", 2, "22.00", "1580.50", 1846, 2900, 22, 45, 4);
        m.prev = record("退潮", 1, "22.00", "1580.50", 1846, 2900, 22, 45, 4);

        String md = ReviewDocFormatter.render(m);
        assertTrue(md.contains("| 连板高度 | 4 | 4 | 持平 |"), md);
        assertTrue(md.contains("| 成交额(亿) | 1580.50 | 1580.50 | 持平 |"), md);
    }

    @Test
    void compareTableFallsBackToDashWithoutPreviousDay() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("退潮", 2, "22.00", "1580.50", 1846, 2900, 22, 45, 4);

        assertTrue(ReviewDocFormatter.render(m).contains("| 温度(°) | 22.0 | — | — |"));
    }

    // ---- 连板梯队 ----

    /** ladder 的来源是 reverseOrder TreeMap，高度降序是上游契约，渲染层按序成行即可。 */
    @Test
    void ladderGroupsByBoardHeight() {
        MarketStocksVO s = new MarketStocksVO();
        s.setAvailable(true);
        s.setTradeDate(FRIDAY);
        s.setFirstBoardCount(31);
        s.setGapBoards(Arrays.asList(3));
        s.setLadder(Arrays.asList(
                tier(5, "002909", "集泰股份"),
                tier(4, "603221", "爱丽家居", "002156", "通富微电"),
                tier(2, "920014", "特一股份")));
        s.setLimitDownCount(1);
        s.setLimitDown(Arrays.asList(item("300114", "中恒电气", "-19.98")));
        s.setBigLoss(Arrays.asList(item("002156", "通富微电", "-8.20")));

        Model m = new Model();
        m.date = FRIDAY;
        m.stocks = s;

        String md = ReviewDocFormatter.render(m);

        assertTrue(md.contains("- **连板梯队**：5板·集泰股份 ｜ 4板·爱丽家居、通富微电 ｜ 2板·特一股份"), md);
        assertTrue(md.contains("- **首板**：31 家（断档 3 板）"), md);
        assertTrue(md.contains("- **跌停 1 家**：中恒电气"), md);
        assertTrue(md.contains("- **大面**：通富微电"), md);
    }

    /** available=false 时诚实说没有明细，不给空名单——空名单读起来像"今天真没连板"。 */
    @Test
    void unavailableStocksDetailSaysSo() {
        MarketStocksVO s = new MarketStocksVO();
        s.setAvailable(false);

        Model m = new Model();
        m.date = FRIDAY;
        m.stocks = s;

        String md = ReviewDocFormatter.render(m);
        assertTrue(md.contains("- **连板梯队**：—（这天没有盘面明细"), md);
        assertFalse(md.contains("首板"), md);
    }

    // ---- 持仓 / 预判 / 锚点 / 题材 ----

    @Test
    void positionAndPredictionRowsCarryTheirOwnCells() {
        Position p = new Position();
        p.setStockCode("002909");
        p.setStockName("集泰股份");
        p.setCostPrice(new BigDecimal("12.30"));
        p.setCurrentPrice(new BigDecimal("13.400"));
        p.setFloatPct(new BigDecimal("8.94"));
        p.setAction("持有");
        p.setPlannedAction("冲高减半");
        p.setDiscipline("应做未做");

        Model m = new Model();
        m.date = FRIDAY;
        m.positions = Arrays.asList(p);
        m.predictions = Arrays.asList(
                answer("路径二", "落空", "跌停扩至 17 家"),
                plan("退潮延续", 55, "竞业达低开低走+跌停≥20"));
        m.anchors = Arrays.asList(anchor("002712", "竞业达", "CYCLE"));

        String md = ReviewDocFormatter.render(m);

        assertTrue(md.contains("| 集泰股份 002909 | 12.30 | 13.40 | +8.94% | 持有 | 冲高减半 | 应做未做 |"), md);
        assertTrue(md.contains("| 路径二 | 落空 | 跌停扩至 17 家 |"), md);
        assertTrue(md.contains("- **退潮延续**：概率 55% ｜触发 竞业达低开低走+跌停≥20"), md);
        assertTrue(md.contains("- **竞业达（002712）** · 周期阵眼 · 8/28 起在位：连续三天跌停板未破"), md);
        // PLAN 与 ANSWER 是两张表，串了节这份文档就没法用
        assertFalse(section(md, "【五、", "【六、").contains("退潮延续"), md);
        assertFalse(section(md, "【六、", "【七、").contains("路径二"), md);
    }

    /** 明日预判那一节必须写清是哪一个交易日，否则整节的"次日"没有落点。 */
    @Test
    void planSectionNamesTheNextSessionSkippingWeekend() {
        Model m = new Model();
        m.date = FRIDAY;

        assertTrue(ReviewDocFormatter.render(m).contains("## 【六、周一（9/7）预期 · 三路径】"));
    }

    /** 题材无日粒度：只有那天导入过原文才带得出来，带不出来就明说，不拿行业分布冒充。 */
    @Test
    void themesOnlyAppearWhenThatDayStoredThem() {
        Model with = new Model();
        with.date = FRIDAY;
        with.themes = Arrays.asList(new ThemeRow(1, "液冷服务器", 70, "扩散", "002909", "集泰股份"));

        String md = ReviewDocFormatter.render(with);
        assertTrue(md.contains("  - 液冷服务器 强度 70 · 扩散 · 龙头 集泰股份"), md);
        assertFalse(md.contains("无从带出"), md);

        Model without = new Model();
        without.date = FRIDAY;
        assertTrue(ReviewDocFormatter.render(without).contains("无从带出"));
    }

    /**
     * 判断文字不再从库里回填（{@code doc_notes} 已停用）：列里存着历史值也不许漏进这份文档。
     * 表现必须是八节各一行 {@code ✍️ 判断} 占位，而不是原文——他会在这份 md 里现写，写完导入。
     */
    @Test
    void storedDocNotesAreNeverRenderedBack() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("退潮", 2, "22.00", "1580.50", 1846, 2900, 22, 45, 4);
        m.today.setDocNotes("{\"index\":\"哨兵定性\",\"theme\":\"哨兵翻译\",\"旧主线\":\"挂了个不相干键的正文\"}");

        String md = ReviewDocFormatter.render(m);

        assertEquals(8, countOf(md, "✍️ 判断"), "每节都该是占位：\n----\n" + md);
        assertFalse(md.contains("哨兵"), "存过的判断文字漏进导出了\n----\n" + md);
        assertFalse(md.contains("**判断**"), md);
        assertFalse(md.contains("未归节"), md);
    }

    // ---- 幂等 ----

    @Test
    void renderingTwiceIsByteIdentical() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("退潮", 2, "22.00", "1580.50", 1846, 2900, 22, 45, 4);
        m.stocks = stocks();
        m.positions = Arrays.asList(new Position());

        String first = ReviewDocFormatter.render(m);
        assertEquals(first, ReviewDocFormatter.render(m));
        assertTrue(first.endsWith("自己负责。\n"), "结尾该收在免责声明");
    }

    // ---- fixture ----

    private static DailyRecord record(String stage, Integer seq, String temperature, String totalVolume,
                                     int up, int down, int limitUp, int limitDown, int maxBoard) {
        DailyRecord r = new DailyRecord();
        r.setTradeDate(FRIDAY);
        r.setStage(stage);
        r.setStageSeq(seq);
        r.setTemperature(new BigDecimal(temperature));
        r.setTotalVolume(new BigDecimal(totalVolume));
        r.setUpCount(up);
        r.setDownCount(down);
        r.setLimitUpCount(limitUp);
        r.setLimitDownCount(limitDown);
        r.setMaxConsecutiveLimit(maxBoard);
        return r;
    }

    private static IndexClose index(String code, String close, String changePct) {
        IndexClose ic = new IndexClose();
        ic.setIndexCode(code);
        ic.setClosePrice(new BigDecimal(close));
        ic.setChangePct(new BigDecimal(changePct));
        return ic;
    }

    private static MarketStocksVO.Tier tier(int board, String... codeAndName) {
        MarketStocksVO.Tier t = new MarketStocksVO.Tier();
        t.setBoard(board);
        List<MarketStocksVO.Item> items = new ArrayList<>();
        for (int i = 0; i < codeAndName.length; i += 2) {
            items.add(item(codeAndName[i], codeAndName[i + 1], null));
        }
        t.setStocks(items);
        return t;
    }

    private static MarketStocksVO.Item item(String code, String name, String pct) {
        MarketStocksVO.Item it = new MarketStocksVO.Item();
        it.setCode(code);
        it.setName(name);
        it.setPct(pct == null ? null : new BigDecimal(pct));
        return it;
    }

    private static MarketStocksVO stocks() {
        MarketStocksVO s = new MarketStocksVO();
        s.setAvailable(true);
        s.setFirstBoardCount(12);
        s.setLadder(Arrays.asList(tier(3, "002909", "集泰股份")));
        return s;
    }

    private static Prediction plan(String name, Integer prob, String condition) {
        Prediction p = new Prediction();
        p.setKind(Prediction.KIND_PLAN);
        p.setName(name);
        p.setProb(prob);
        p.setConditionText(condition);
        return p;
    }

    private static Prediction answer(String name, String result, String note) {
        Prediction p = new Prediction();
        p.setKind(Prediction.KIND_ANSWER);
        p.setName(name);
        p.setResult(result);
        p.setResultNote(note);
        return p;
    }

    private static Anchor anchor(String code, String name, String role) {
        Anchor a = new Anchor();
        a.setStockCode(code);
        a.setStockName(name);
        a.setRole(role);
        a.setStartDate(LocalDate.of(2026, 8, 28));
        a.setNote("连续三天跌停板未破");
        return a;
    }

    /** 取两个小节标题之间的那段，用来验证内容没串节。 */
    private static String section(String md, String fromHeading, String toHeading) {
        int from = md.indexOf(fromHeading);
        int to = md.indexOf(toHeading);
        assertTrue(from >= 0 && to > from, "找不到小节 " + fromHeading + "\n----\n" + md);
        return md.substring(from, to);
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
