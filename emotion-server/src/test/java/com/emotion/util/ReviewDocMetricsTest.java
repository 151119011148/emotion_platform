package com.emotion.util;

import com.emotion.entity.DailyRecord;
import com.emotion.vo.MarketStocksVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派生读数的算术。这几个数他每天要拿去和开盘啦/东财对，所以断言的重心是<b>分母</b>和
 * <b>缺数时的 null</b>：缺数写成 0 就是告诉他"今天真的零家"，那是假话。
 */
class ReviewDocMetricsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 4);

    @Test
    void ratesRoundToIntegerPercentAndStateTheirOwnDenominator() {
        ReviewDocMetrics x = ReviewDocMetrics.from(record("1846", "2900", "1980.50"),
                record("3100", "1900", "2150.00"), 66, 24, available(), null);

        // 1846/4746 = 38.9 → 39；分母不含平盘，所以比他手记里的 898/5562 略高
        assertEquals(39, x.redRate.intValue());
        // 66/90 = 73.3 → 73
        assertEquals(73, x.sealRate.intValue());
        assertEquals(new BigDecimal("-169.50"), x.volumeDelta);
        assertEquals(new BigDecimal("1980.50"), x.volume);
    }

    /** 半个读数不配叫比率：涨跌家数缺一个，红盘率就是 null，不是 0%。 */
    @Test
    void halfABreadthReadingIsNotARate() {
        DailyRecord r = record("1846", "2900", "1980.50");
        r.setDownCount(null);

        assertNull(ReviewDocMetrics.from(r, null, null, null, null, null).redRate);
    }

    /** 全市场零涨跌（停机、坏数据）时不写一个看着像数的 0%。 */
    @Test
    void zeroDenominatorIsNotARate() {
        assertNull(ReviewDocMetrics.from(record("0", "0", "0"), null, 0, 0, null, null).redRate);
        assertNull(ReviewDocMetrics.from(record("0", "0", "0"), null, 0, 0, null, null).sealRate);
    }

    /** 池计数没拿到时封板率 null——这天根本没拉过盘面明细，和"0% 封板率"是两回事。 */
    @Test
    void missingPoolCountsStayMissing() {
        ReviewDocMetrics x = ReviewDocMetrics.from(record("1846", "2900", "1980.50"), null,
                null, null, available(), null);

        assertNull(x.sealRate);
    }

    /** 有明细但全是首板：连板家数是真的 0，不能退成 null。 */
    @Test
    void emptyLadderWithDetailIsARealZero() {
        MarketStocksVO s = new MarketStocksVO();
        s.setAvailable(true);

        assertEquals(0, ReviewDocMetrics.from(null, null, null, null, s, null).lianbanCount.intValue());
        assertNull(ReviewDocMetrics.from(null, null, null, null, unavailable(), null).lianbanCount);
    }

    /** 一字数由服务层从<b>整个涨停池</b>数好传进来；这里只守 null 和 0 的区别：0 是"今天真的一家一字没有"。 */
    @Test
    void yiziPassedThroughFromTheWholePool() {
        assertEquals(6, ReviewDocMetrics.from(null, null, null, null, null, 6).yiziCount.intValue());
        assertEquals(0, ReviewDocMetrics.from(null, null, null, null, null, 0).yiziCount.intValue());
        assertNull(ReviewDocMetrics.from(null, null, null, null, null, null).yiziCount);
    }

    /** 上一记录未必是昨天，但量能差只有它可算；缺任一侧就 null。 */
    @Test
    void volumeDeltaNeedsBothSides() {
        assertNull(ReviewDocMetrics.from(record("1", "1", "1980.50"), null, null, null, null, null).volumeDelta);

        DailyRecord noVolume = record("1", "1", "1980.50");
        noVolume.setTotalVolume(null);
        assertNull(ReviewDocMetrics.from(noVolume, record("1", "1", "2150.00"), null, null, null, null).volumeDelta);
    }

    /** 弱方向分桶：家数降序、大面并进同名行、行业缺失归「未分类」而不是丢行。 */
    @Test
    void weakSectorsSortByHeadcountAndKeepUnmapped() {
        MarketStocksVO s = new MarketStocksVO();
        s.setAvailable(true);
        s.setLimitDown(Arrays.asList(
                item("300114", "中恒电气", "-19.98", "电气设备"),
                item("600589", "大位科技", "-10", null),
                item("000001", "平安银行", "-9.99", "  ")));
        s.setBigLoss(Arrays.asList(
                item("300114", "中恒电气", "-8.20", "电气设备"),
                item("000002", "万科Ａ", "-7.50", "房地产")));

        List<ReviewDocMetrics.Sector> out = ReviewDocMetrics.weakSectors(s);

        // null 和纯空白都归同一个「未分类」桶：宁可不分类，也不把它们塞进别的行业，也不丢行
        assertEquals(Arrays.asList("未分类", "电气设备"), names(out));
        assertEquals(2, out.size());
        assertEquals(Arrays.asList("大位科技(-10%)", "平安银行(-9.99%)"), out.get(0).leaders);
        assertEquals(1, out.get(1).bigLossCount);
        // 只有大面、当天没有跌停的行业（房地产）不单独成行：那张表数的是跌停
        String rendered = md(s);
        assertTrue(rendered.contains("| 电气设备 | 1 | 1 | 中恒电气(-19.98%) | ✍️ |"), rendered);
        assertFalse(rendered.contains("| 房地产 |"), rendered);
    }

    /** 一份全空的派生读数：每个数都是 null，不是 0。 */
    @Test
    void emptyMetricsIsAllNulls() {
        ReviewDocMetrics x = ReviewDocMetrics.empty();

        assertNull(x.redRate);
        assertNull(x.sealRate);
        assertNull(x.lianbanCount);
        assertNull(x.yiziCount);
        assertNull(x.volumeDelta);
        assertTrue(x.weakSectors.isEmpty());
    }

    // ---- fixture ----

    private static List<String> names(List<ReviewDocMetrics.Sector> sectors) {
        List<String> out = new ArrayList<>();
        for (ReviewDocMetrics.Sector s : sectors) {
            out.add(s.industry);
        }
        return out;
    }

    private static DailyRecord record(String up, String down, String volume) {
        DailyRecord r = new DailyRecord();
        r.setTradeDate(DAY);
        r.setUpCount(Integer.valueOf(up));
        r.setDownCount(Integer.valueOf(down));
        r.setTotalVolume(new BigDecimal(volume));
        return r;
    }

    private static MarketStocksVO available() {
        MarketStocksVO s = new MarketStocksVO();
        s.setAvailable(true);
        MarketStocksVO.Tier t = new MarketStocksVO.Tier();
        t.setBoard(3);
        t.setStocks(Arrays.asList(item("603843", "宋都股份", "10.02", "通信设备")));
        s.setLadder(Arrays.asList(t));
        return s;
    }

    private static MarketStocksVO unavailable() {
        MarketStocksVO s = new MarketStocksVO();
        s.setAvailable(false);
        return s;
    }

    private static MarketStocksVO.Item item(String code, String name, String pct, String industry) {
        MarketStocksVO.Item it = new MarketStocksVO.Item();
        it.setCode(code);
        it.setName(name);
        it.setPct(new BigDecimal(pct));
        it.setIndustry(industry);
        return it;
    }

    /** 借用渲染层拿一份 md，只为确认没有明细时不会凭空多出板块行。 */
    private static String md(MarketStocksVO s) {
        ReviewDocFormatter.Model m = new ReviewDocFormatter.Model();
        m.date = DAY;
        m.stocks = s;
        m.metrics = ReviewDocMetrics.from(null, null, null, null, s, null);
        return ReviewDocFormatter.render(m);
    }
}
