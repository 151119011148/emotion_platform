package com.emotion.util;

import com.emotion.entity.Anchor;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.IndustrySnapshot;
import com.emotion.entity.IndexClose;
import com.emotion.entity.Position;
import com.emotion.entity.Prediction;
import com.emotion.entity.ThemeSnapshot;
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
 * 只读复盘文档渲染层。版式是用户手写的十节当日＋五节次日那一套（这里 11 节 + 免责），
 * 断言的重心在两件事：
 * <b>任何一块数据缺了都不能炸</b>（这按钮就是要在半空的日子上被点），
 * 以及<b>有数据时数字必须按系统口径落进他熟悉的版式</b>（错了就是让他抄错一遍）。
 */
class ReviewDocFormatterTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 4);

    /**
     * 他那份手记的骨架：当日五节 + 结案一节 + 次日五节 + 免责，顺序不能乱。
     * 只比到「【N、」这一层——带日期的那半截在缺数据的日子会换成"上一记录""—"，完整标题由各自的用例钉。
     */
    private static final String[] SECTION_HEADINGS = {
            "## 【一、", "## 【二、", "## 【三、", "## 【四、", "## 【五、", "## 【六、",
            "## 【七、", "## 【八、", "## 【九、", "## 【十、", "## 【十一、", "## 【附、免责声明】"
    };

    // ---- 缺数据 ----

    /** 光杆一个 date：十二个小节按序齐全、判断占位在、数据处一律 —，一个异常都不许抛。 */
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
        // 次日那一半也得有落点：没有 nextDate 时按"跳过周末"的日历近似
        assertTrue(md.startsWith("# 9/4（周五）完整复盘 + 9/7（周一）预期定性"), md);
        // 十一节每节一行判断占位（免责声明那节是系统口径说明，不占位）
        assertEquals(11, countOf(md, "✍️ 判断"), md);
        // 整天没有读数时给一句说明，比五行各自一个 — 更好读
        assertTrue(md.contains("> 这天还没有系统读数"), md);
        assertTrue(md.contains("- **上证指数**：—"), md);
        assertTrue(md.contains("- **连板梯队**：—（这天没有盘面明细"), md);
        assertTrue(md.contains("| 温度(°) | — | — | — |"), md);
        assertTrue(md.contains("| 指标 | 今日 9/4 | 上一记录 | 变化 |"), md);
        assertTrue(md.contains("> 当日实际读数：—（这天没有系统读数）"), md);
        assertTrue(md.contains("| — | —（这天既没有在册阵眼也没有持仓） | ✍️ |"), md);
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
        m.coreThemes = null;
        m.industries = null;
        m.metrics = null;

        assertTrue(ReviewDocFormatter.render(m).contains("【附、免责声明】"));
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
        m.today.setMidCapStock("中际旭创");
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
        assertTrue(md.contains("- **上涨/下跌**：1846 / 2900（红盘率 39%，分母不含平盘）"), md);
        assertTrue(md.contains("- **涨停/跌停**：22 / 45"), md);
        assertTrue(md.contains("- **主线**：液冷服务器"), md);
        assertTrue(md.contains("｜ **总龙头**：集泰股份 · 晋级失败 ｜ **中军**：中际旭创"), md);
        assertTrue(md.contains("温度 22° · 阶段 退潮 · 二阶段 · 总分 —"), md);
        // 九维是这份模型的骨架，只在仪表盘上有、导出文档里没有，等于把最值钱的一栏丢了
        String three = section(md, "【三、", "【四、");
        assertTrue(three.contains("| 1 连板高度 | 4 | 3 |"), md);
        assertTrue(three.contains("第 9 维的分不单列存储"), md);
    }

    /** 已人工改判必须在标题上留痕，否则这份 md 会和仪表盘对不上而看不出为什么。 */
    @Test
    void overriddenStageIsMarked() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("分歧", 1, "44.00", "1580.50", 2600, 2100, 40, 12, 5);
        m.today.setStageOverridden(1);

        assertTrue(ReviewDocFormatter.render(m).startsWith("# 9/4（周五）完整复盘 + 9/7（周一）预期定性 · 分歧 · 一阶段（已人工改判）"));
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
        assertTrue(md.contains("（红盘率 66%，分母不含平盘）"), md);
        assertTrue(md.contains("| 涨停家数 | 60 | — | — |"), md);
        assertTrue(md.contains("总分 9 · 进分 9/9 维"), md);
    }

    // ---- 派生读数：红盘率 / 封板率 / 连板家数 / 一字 / 量能差 ----

    /**
     * 这几个数库里没有列，是他每天手抄的。算错一个就是让他照着抄错，所以连分母一起断言。
     */
    @Test
    void derivedMetricsLandInFirstSection() {
        Model m = rich();

        String one = section(ReviewDocFormatter.render(m), "【一、", "【二、");

        assertTrue(one.contains("- **上涨/下跌**：1846 / 2900（红盘率 39%，分母不含平盘）"), one);
        // 涨停/跌停那行仍是 t_daily_record 的口径，一字和连板家数来自盘面明细，两把数不能混
        assertTrue(one.contains("- **涨停/跌停**：66 / 24"), one);
        assertTrue(one.contains("- **连板**：5 家 2 板及以上｜最高 6 板｜一字 9 家"), one);
        assertTrue(one.contains("- **封板率**：73%（涨停池 /(涨停池 + 炸板池)，炸板 24 家）"), one);
        assertTrue(one.contains("- **全市场成交**：1980.5 亿（较上一记录 -169.5 亿）"), one);
    }

    /** 没拉过盘面明细时，封板率／连板家数／一字一律 —，不许写成 0 或 0%。 */
    @Test
    void derivedMetricsStayBlankWithoutDetail() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("退潮", 2, "22.00", "1580.50", 1846, 2900, 22, 45, 4);

        String one = section(ReviewDocFormatter.render(m), "【一、", "【二、");

        assertTrue(one.contains("- **连板**：— 家 2 板及以上｜最高 4 板｜一字 — 家"), one);
        assertTrue(one.contains("- **封板率**：—（涨停池 /(涨停池 + 炸板池)，炸板 — 家）"), one);
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
        assertTrue(md.contains("| 红盘率(%) | 39 | 51 | -12 |"), md);
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

    /** 红盘率的分母和他手记里的不是同一个，这句话必须出现在对照表旁边，不然他会对不上数。 */
    @Test
    void redRateDenominatorCaveatIsStated() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("退潮", 2, "22.00", "1580.50", 1846, 2900, 22, 45, 4);

        String three = section(ReviewDocFormatter.render(m), "【三、", "【四、");
        assertTrue(three.contains("<b>库里没有平盘家数</b>"), three);
        assertTrue(three.contains("这个数会比含平盘的读数略高"), three);
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
                tier(5, "002909", "集泰股份", "装修装饰"),
                tier(4, "603221", "爱丽家居", "家居用品", "002156", "通富微电", "半导体"),
                tier(2, "920014", "特一股份", "汽车零部件")));
        s.setLimitDownCount(1);
        s.setLimitDown(Arrays.asList(item("300114", "中恒电气", "-19.98", "电气设备")));
        s.setBigLoss(Arrays.asList(item("002156", "通富微电", "-8.20", "半导体")));

        Model m = new Model();
        m.date = FRIDAY;
        m.stocks = s;

        String md = ReviewDocFormatter.render(m);

        // 一档一行：他要扫的是"哪几个高度有票、分别属于哪条线"，挤成一串读不出来
        String three = section(md, "【三、", "【四、");
        assertTrue(three.contains("- **5 板**：集泰股份(装修装饰)"), three);
        assertTrue(three.contains("- **4 板**：爱丽家居(家居用品)、通富微电(半导体)"), three);
        assertTrue(three.contains("- **2 板**：特一股份(汽车零部件)"), three);
        assertTrue(three.contains("- **首板**：31 家（断档 3 板）"), three);
        assertTrue(three.contains("- **跌停 1 家**：中恒电气"), three);
        assertTrue(three.contains("- **大面**：通富微电"), three);
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

    // ---- 【二】三张表：核心题材 / 板块涨停榜 / 弱方向 ----

    @Test
    void coreThemeTableCarriesItsCells() {
        Model m = rich();

        String two = section(ReviewDocFormatter.render(m), "【二、", "【三、");

        assertTrue(two.contains("| 题材 | 强度 | 涨停 | 最高板 | 持续 | 龙头 | 生命周期 | 定性 |"), two);
        assertTrue(two.contains("| 光通信/CPO | 92.5 | 12 | 6 | 4 天 | 宋都股份 6 板 | 启动 | ✍️ |"), two);
        // 没登记龙头的题材：龙头那格是 —，不能整行丢掉
        assertTrue(two.contains("| 固态电池 | 38 | 3 | 2 | 5 天 | — | 退潮 | ✍️ |"), two);
    }

    @Test
    void industryBoardTableShowsTopFiveOnly() {
        Model m = rich();
        m.industries.add(industry("第四名", 4, 2, "2", 0, 0));
        m.industries.add(industry("第五名", 3, 1, "1", 0, 0));
        m.industries.add(industry("第六名", 2, 1, "1", 0, 0));
        m.industries.add(industry("第七名", 1, 1, "0", 0, 0));

        String two = section(ReviewDocFormatter.render(m), "【二、", "【三、");

        assertTrue(two.contains("| 通信设备 | 12 | 6 | 4 | 0 | 6,5 |"), two);
        assertTrue(two.contains("| 通用机械 | 7 | 3 | 2 | 3 | 3 |"), two);
        assertEquals(1, countOf(two, "| 行业 | 涨停 | 最高板 | 一字 | 大面 | 梯队 |"), "表头只该出现一次");
        assertTrue(two.contains("第五名"), two);
        assertFalse(two.contains("第六名"), "板块榜只给前 5：" + two);
    }

    /** 跌停按行业归桶：家数降序、大面并进同名行、代表票带当日跌幅。 */
    @Test
    void weakSectorsBucketLimitDownByIndustry() {
        Model m = rich();

        String two = section(ReviewDocFormatter.render(m), "【二、", "【三、");

        assertTrue(two.contains("| 行业(东财) | 跌停 | 其中大面 | 代表票(当日跌幅) | 定性 |"), two);
        assertTrue(two.contains("| 电气设备 | 2 | — | 中恒电气(-19.98%)、大位科技(-10%) | ✍️ |"), two);
        assertTrue(two.contains("| 半导体 | 1 | 1 | 通富微电(-10.02%) | ✍️ |"), two);
        // 这是东财行业口径，和他的题材名不是一回事，标题里就得说清
        assertTrue(two.contains("行业是东财口径，不等于题材名"), two);
    }

    /** 旧 md 的 `题材:` 键单独成块，不与系统盘出来的 Top5 混排。 */
    @Test
    void legacyThemeRowsStaySeparateFromSnapshot() {
        Model with = rich();
        String md = ReviewDocFormatter.render(with);
        assertTrue(md.contains("**那天 md 里存过的题材行**（旧 `题材:` 键"), md);
        assertTrue(md.contains("  - 光通信 强度 88 · 主升 · 龙头 宋都股份"), md);

        Model without = new Model();
        without.date = FRIDAY;
        assertFalse(ReviewDocFormatter.render(without).contains("旧 `题材:` 键"));
    }

    // ---- 持仓 / 预判 / 锚点 ----

    @Test
    void positionAndPredictionRowsCarryTheirOwnCells() {
        Model m = rich();

        String md = ReviewDocFormatter.render(m);

        // 卖价与现价并排：89.10 才是真成交的价，90.50 是收盘，两者不等就是要看出来的差价。
        assertTrue(md.contains("| 源杰科技 002909 | 88.00 | 90.50 | 89.10 | 1000 | +2.84% | 减半 | 减半 | 按纪律 |"), md);
        assertTrue(md.contains("| 标的 | 成本 | 现价 | 卖价 | 卖出量 | 浮动 | 动作 | 应做 | 纪律 |"), md);
        assertTrue(md.contains("| 跌停≥30 家 | 未触发 | 实际 24 家，低估了韧性 |"), md);
        assertTrue(md.contains("- **分歧转一致**：概率 40% ｜触发 红盘率>50% 且 5 板股晋级 ｜动作 ✍️"), md);
        // PLAN 与 ANSWER 分属【八】和【六】两张表，串了节这份文档就没法用
        assertFalse(section(md, "【六、", "【七、").contains("分歧转一致"), md);
        assertFalse(section(md, "【七、", "【九、").contains("跌停≥30 家"), md);
    }

    /** 【六】是"上一天的预案在今天结案"，两个日期都得写出来，否则不知道在结哪一天的案。 */
    @Test
    void answerSectionNamesBothDates() {
        Model m = rich();

        String six = section(ReviewDocFormatter.render(m), "【六、", "【七、");

        assertTrue(six.startsWith("【六、9/3 预案验证 · 9/4 结案】"), six);
        // 结案要对着的读数系统给出来，省得他翻页
        assertTrue(six.contains("> 当日实际读数：涨停 66 / 跌停 24 / 红盘率 39% / 连板 5 家"
                + " / 最高 6 板 / 成交 1980.5 亿 / 温度 44° / 阶段 分歧"), six);
    }

    /** 次日预判那一节必须写清是哪一个交易日，否则整节的"次日"没有落点。 */
    @Test
    void planSectionNamesTheNextSessionSkippingWeekend() {
        Model m = new Model();
        m.date = FRIDAY;

        assertTrue(ReviewDocFormatter.render(m).contains("## 【八、9/7（周一）三路径】"));
    }

    /** 服务层给了交易日历上的次日，就以它为准，不再用"跳过周末"的近似。 */
    @Test
    void explicitNextDateWinsOverCalendarApproximation() {
        Model m = new Model();
        m.date = FRIDAY;
        m.nextDate = LocalDate.of(2026, 10, 9);

        String md = ReviewDocFormatter.render(m);
        assertTrue(md.contains("## 【八、10/9（周五）三路径】"), md);
        assertTrue(md.startsWith("# 9/4（周五）完整复盘 + 10/9（周五）预期定性"), md);
    }

    @Test
    void anchorSectionListsCycleAnchorsAndPositions() {
        Model m = rich();

        String nine = section(ReviewDocFormatter.render(m), "【九、", "【十、");

        assertTrue(nine.contains("| — | 宋都股份 603843（周期阵眼 · 8/28 起在位） | 连续三天跌停板未破 |"), nine);
        assertTrue(nine.contains("（周期总龙 · 8/28 起在位）"), nine);
        // 持仓里没在册阵眼的票补一行"持仓位"
        assertTrue(nine.contains("| 持仓位 | 新赛股份 001234（现价 9.90） | ✍️ |"), nine);
        // 同一只票既是阵眼又持仓时只留锚点那一行，并排两行读起来像渲染坏了
        assertEquals(0, countOf(nine, "持仓位 | 宋都股份"), nine);
        assertTrue(nine.contains("不是每天的题材位"), nine);
    }

    /** 【十】的四档来自 plan_open/break/low/fall；分档轴是开盘形态，和他手记的价位档不是一回事。 */
    @Test
    void nextDayPositionTableShowsFourBucketsAndTheCaveat() {
        Model m = rich();

        String ten = section(ReviewDocFormatter.render(m), "【十、", "【十一、");

        assertTrue(ten.contains("| 标的 | 现价 | 高开 | 炸板 | 平开/低开 | 跌停 | 总纲 |"), ten);
        assertTrue(ten.contains("| 宋都股份 603843 | 13.40 | 竞价>3% 留半仓 | 开板即清 | 平开走 | 跌停竞价清 | 不板清 |"), ten);
        assertTrue(ten.contains("这张表的分档轴是形态不是价位"), ten);
    }

    /** 【十一】的列每天换，系统只代填"全局读数"那一格，其余一律 ✍️。 */
    @Test
    void auctionTableFillsOnlyTheGlobalReading() {
        Model m = rich();

        String eleven = section(ReviewDocFormatter.render(m), "【十一、", "【附、");

        assertTrue(eleven.contains("参考【一】：涨停 66 / 跌停 24 / 红盘率 39%"), eleven);
        assertTrue(eleven.contains("| ✍️ | ✍️ | ✍️ |"), eleven);
        assertTrue(eleven.contains("列每天换"), eleven);
    }

    /**
     * 判断文字不再从库里回填（{@code doc_notes} 已停用）：列里存着历史值也不许漏进这份文档。
     * 表现必须是十一节各一行 {@code ✍️ 判断} 占位，而不是原文——他会在这份 md 里现写，写完导入。
     */
    @Test
    void storedDocNotesAreNeverRenderedBack() {
        Model m = rich();
        m.today.setDocNotes("{\"index\":\"哨兵定性\",\"theme\":\"哨兵翻译\",\"旧主线\":\"挂了个不相干键的正文\"}");

        String md = ReviewDocFormatter.render(m);

        assertEquals(11, countOf(md, "✍️ 判断"), "每节都该是占位：\n----\n" + md);
        assertFalse(md.contains("哨兵"), "存过的判断文字漏进导出了\n----\n" + md);
        assertFalse(md.contains("**判断**"), md);
        assertFalse(md.contains("未归节"), md);
    }

    // ---- 幂等 ----

    @Test
    void renderingTwiceIsByteIdentical() {
        Model m = rich();

        String first = ReviewDocFormatter.render(m);
        assertEquals(first, ReviewDocFormatter.render(m));
        assertTrue(first.endsWith("自己负责。\n"), "结尾该收在免责声明");
    }

    // ---- fixture ----

    /** 一块数据都不缺的一天：十一节全有内容，派生数也算得出来。 */
    private static Model rich() {
        Model m = new Model();
        m.date = FRIDAY;
        m.today = record("分歧", 4, "44.00", "1980.50", 1846, 2900, 66, 24, 6);
        m.today.setMainTheme("光通信/CPO");
        m.today.setLeadingStock("剑桥科技");
        m.today.setLeadingStockStatus("加速");
        m.today.setMidCapStock("中际旭创");
        m.prev = record("分歧", 4, "61.00", "2150.00", 3100, 1900, 58, 6, 5);
        m.prev.setTradeDate(LocalDate.of(2026, 9, 3));
        m.indexes = Arrays.asList(
                index("000001", "3842.090", "-0.72"),
                index("399006", "1521.400", "0"));
        m.stocks = stocks();
        m.positions = Arrays.asList(position(), soldDown(), plainHolding());
        m.predictions = Arrays.asList(
                plan("分歧转一致", 40, "红盘率>50% 且 5 板股晋级"),
                answer("跌停≥30 家", "未触发", "实际 24 家，低估了韧性"));
        m.anchors = Arrays.asList(anchor("603843", "宋都股份", "CYCLE"), anchor("002909", "源杰科技", "LEADER"));
        m.coreThemes = new ArrayList<>(Arrays.asList(
                theme(2, "机器人", "61.0", 7, 3, 2, "扩散", "002909", "源杰科技"),
                theme(1, "光通信/CPO", "92.5", 12, 6, 4, "启动", "603843", "宋都股份"),
                theme(3, "固态电池", "38.0", 3, 2, 5, "退潮", null, null)));
        m.industries = new ArrayList<>(Arrays.asList(
                industry("通信设备", 12, 6, "6,5", 4, 0),
                industry("通用机械", 7, 3, "3", 2, 3),
                industry("电子", 5, 2, "2", 1, 1)));
        m.themes = Arrays.asList(new ThemeRow(1, "光通信", 88, "主升", "603843", "宋都股份"));
        m.poolZt = 66;
        m.poolZb = 24;
        // 一字 9 家：故意不等于上面三行板块快照的一字合计（4+2+1=7）。快照只落前 5 个板块，
        // 拿它的合计当全市场一字数就是 9/30 那次少算的来路，所以这里两个数必须错开。
        m.metrics = ReviewDocMetrics.from(m.today, m.prev, m.poolZt, m.poolZb, m.stocks, 9);
        m.nextDate = LocalDate.of(2026, 9, 7);
        return m;
    }

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
        r.setScoreHeight(3);
        return r;
    }

    private static IndexClose index(String code, String close, String changePct) {
        IndexClose ic = new IndexClose();
        ic.setIndexCode(code);
        ic.setClosePrice(new BigDecimal(close));
        ic.setChangePct(new BigDecimal(changePct));
        return ic;
    }

    /** 梯队：5 条档、6 只票，其中 4 板两只是同行业还是不同行业都影响渲染断言，这里给不同的。 */
    private static MarketStocksVO stocks() {
        MarketStocksVO s = new MarketStocksVO();
        s.setAvailable(true);
        s.setTradeDate(FRIDAY);
        s.setFirstBoardCount(31);
        s.setGapBoards(Arrays.asList(4));
        s.setLadder(Arrays.asList(
                tier(6, "603843", "宋都股份", "通信设备"),
                tier(5, "002909", "源杰科技", "通信设备"),
                tier(3, "001234", "新赛股份", "纺织"),
                tier(2, "600123", "某华传媒", "传媒"),
                tier(2, "002567", "某半导体", "半导体")));
        s.setLimitDownCount(3);
        s.setLimitDown(Arrays.asList(
                item("300114", "中恒电气", "-19.98", "电气设备"),
                item("600589", "大位科技", "-10", "电气设备"),
                item("002156", "通富微电", "-10.02", "半导体")));
        s.setBigLoss(Arrays.asList(item("002156", "通富微电", "-8.20", "半导体")));
        return s;
    }

    private static Position position() {
        Position p = new Position();
        p.setStockCode("603843");
        p.setStockName("宋都股份");
        p.setCostPrice(new BigDecimal("12.30"));
        p.setCurrentPrice(new BigDecimal("13.400"));
        p.setFloatPct(new BigDecimal("8.94"));
        p.setAction("持有");
        p.setPlannedAction("冲高减半");
        p.setDiscipline("应做未做");
        p.setNextDayPlan("不板清");
        p.setPlanOpen("竞价>3% 留半仓");
        p.setPlanBreak("开板即清");
        p.setPlanLow("平开走");
        p.setPlanFall("跌停竞价清");
        return p;
    }

    private static Position soldDown() {
        Position p = new Position();
        p.setStockCode("002909");
        p.setStockName("源杰科技");
        p.setCostPrice(new BigDecimal("88.00"));
        p.setCurrentPrice(new BigDecimal("90.50"));
        p.setFloatPct(new BigDecimal("2.84"));
        p.setSellPrice(new BigDecimal("89.10"));
        p.setSellQty(1000);
        p.setAction("减半");
        p.setPlannedAction("减半");
        p.setDiscipline("按纪律");
        return p;
    }

    /** 既没登记成阵眼、也还没填次日四档的持仓：【九】靠它走「持仓位」那行，【十】靠它走全 — 那行。 */
    private static Position plainHolding() {
        Position p = new Position();
        p.setStockCode("001234");
        p.setStockName("新赛股份");
        p.setCurrentPrice(new BigDecimal("9.900"));
        p.setAction("持有");
        return p;
    }

    private static ThemeSnapshot theme(int rank, String name, String strength, Integer zt, Integer maxBoard,
                                       Integer days, String lifecycle, String leaderCode, String leaderName) {
        ThemeSnapshot t = new ThemeSnapshot();
        t.setRank(rank);
        t.setThemeName(name);
        t.setStrength(new BigDecimal(strength));
        t.setZtCount(zt);
        t.setMaxBoard(maxBoard);
        t.setContinuousDays(days);
        t.setLifecycle(lifecycle);
        t.setLeaderCode(leaderCode);
        t.setLeaderName(leaderName);
        t.setLeaderBoard(maxBoard);
        return t;
    }

    private static IndustrySnapshot industry(String name, Integer zt, Integer maxBoard, String tiers,
                                             Integer yizi, Integer bigLoss) {
        IndustrySnapshot i = new IndustrySnapshot();
        i.setIndustry(name);
        i.setZtCount(zt);
        i.setMaxBoard(maxBoard);
        i.setTierLevels(tiers);
        i.setYiziCnt(yizi);
        i.setBigLossCnt(bigLoss);
        return i;
    }

    private static MarketStocksVO.Tier tier(int board, String... codeNameIndustry) {
        MarketStocksVO.Tier t = new MarketStocksVO.Tier();
        t.setBoard(board);
        List<MarketStocksVO.Item> items = new ArrayList<>();
        for (int i = 0; i < codeNameIndustry.length; i += 3) {
            items.add(item(codeNameIndustry[i], codeNameIndustry[i + 1], "10.02", codeNameIndustry[i + 2]));
        }
        t.setStocks(items);
        return t;
    }

    private static MarketStocksVO.Item item(String code, String name, String pct, String industry) {
        MarketStocksVO.Item it = new MarketStocksVO.Item();
        it.setCode(code);
        it.setName(name);
        it.setPct(new BigDecimal(pct));
        it.setIndustry(industry);
        return it;
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
