package com.emotion.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** 只测解析与算式，喂的是 2026-09-04 真实响应存档，全程不联网。 */
class EastmoneyClientTest {

    private final EastmoneyClient client = new EastmoneyClient(null,
            "http://push2ex.eastmoney.com", "http://push2delay.eastmoney.com",
            "https://np-anotice-stock.eastmoney.com", "ut", 200);

    @Test
    void readsLimitUpPoolHeadCountAndHeight() {
        PoolResult pool = client.parsePool(Fixtures.text("pool_limit_up.json"));

        assertTrue(pool.isOk());
        // 09-04 真实值：39 家涨停、最高 5 板
        assertEquals(39, pool.getTc());
        assertEquals(5, pool.maxConsecutive());
        assertEquals(39, pool.getRows().size());
        assertFalse(pool.isTruncated());
    }

    @Test
    void readsBrokenPoolAsBreaksNotHomeCount() {
        PoolResult pool = client.parsePool(Fixtures.text("pool_broken.json"));

        assertTrue(pool.isOk());
        assertEquals(48, pool.getTc());
        // 48 家炸板却炸了 216 次——这两个数不能混用，炸板率用的是后者
        assertEquals(216, MarketMetrics.breakCount(pool.getRows()));
    }

    @Test
    void readsLimitDownPool() {
        PoolResult pool = client.parsePool(Fixtures.text("pool_limit_down.json"));

        assertTrue(pool.isOk());
        assertEquals(9, pool.getTc());
        assertEquals(9, pool.getRows().size());
        assertFalse(pool.isTruncated());
    }

    /**
     * 用错 sort 键时上游就是这个形状：家数给了 9、明细给空，且照样回 rc=0。
     * 必须标成截断，否则"跌停明细一只都没有"会被当成真实情况。
     */
    @Test
    void flagsEmptyRowsWithNonZeroHeadCountAsTruncated() {
        PoolResult pool = client.parsePool("{\"rc\":0,\"data\":{\"tc\":9,\"pool\":[]}}");

        assertTrue(pool.isOk());
        assertEquals(9, pool.getTc());
        assertTrue(pool.isTruncated());
    }

    /** 家数必须来自 tc：上游按 pagesize 截断 rows 时，用 rows.size() 会静默少算。 */
    @Test
    void countsHomeCountFromTcEvenWhenRowsAreTruncated() {
        PoolResult pool = client.parsePool(Fixtures.text("pool_limit_up.json"));
        pool.setRows(pool.getRows().subList(0, 5));
        pool.setTruncated(true);

        assertEquals(39, pool.getTc());
        assertEquals(5, pool.getRows().size());
        assertTrue(pool.isTruncated());
    }

    @Test
    void treatsNullBodyAsFailureNotEmptyPool() {
        PoolResult pool = client.parsePool(null);

        assertFalse(pool.isOk());
        assertEquals("行情源无响应", pool.getReason());
    }

    /** 缺 sort 参数时上游回 rc=102、data=null。这必须是"失败"，不能被读成"当天没有涨停股"。 */
    @Test
    void treatsUnexpectedReturnCodeAsFailure() {
        PoolResult pool = client.parsePool("{\"rc\":102,\"rt\":1,\"data\":null}");

        assertFalse(pool.isOk());
        assertEquals("行情源返回码 102", pool.getReason());
    }

    @Test
    void keepsPoolHeadCountsInSyncWithRealSample() {
        PoolResult up = client.parsePool(Fixtures.text("pool_limit_up.json"));
        PoolResult broken = client.parsePool(Fixtures.text("pool_broken.json"));

        BigDecimal countRate = MarketMetrics.brokenRate(
                MarketMetrics.breakCount(broken.getRows()), up.getTc());
        assertNotNull(countRate);
        assertEquals(0, new BigDecimal("84.7").compareTo(countRate));

        // 家数口径同日只有 55.2%，两档打分档位会因此翻转，所以算式必须能对上
        assertEquals(0, new BigDecimal("55.2")
                .compareTo(MarketMetrics.percent(broken.getTc(), broken.getTc() + up.getTc())));
        assertEquals(153, MarketMetrics.breakCount(up.getRows()));
    }

    @Test
    void countsBigLossesFromRealBrokenPool() {
        PoolResult broken = client.parsePool(Fixtures.text("pool_broken.json"));
        MarketMetrics.BigLoss bigLoss = MarketMetrics.bigLoss(broken.getRows());

        // 大众交通 / 金帝股份 / 江南新材 / 东亚药业：回撤 11.6%~13.2% 且收盘绿盘
        assertEquals(4, bigLoss.getCount());
        assertEquals(0, bigLoss.getUnusable());
        // 家数是从这批行数的，所以落库的名单和卡面上的数字不可能各说一套
        assertEquals(4, bigLoss.getRows().size());
        assertTrue(broken.getRows().containsAll(bigLoss.getRows()));
    }

    @Test
    void dropsDirectionalConvertiblesFromStockList() {
        StockListResult page = client.parseStockPage(Fixtures.text("stock_list.json"));

        assertTrue(page.isOk());
        // 上游这一页 100 行，其中 3 行是定向可转债，不是股票
        assertEquals(97, page.getRows().size());
        assertEquals(3, page.getDropped().size());
        assertTrue(page.getDropped().contains("810014 莱特定转"), "丢弃必须留名，不能静默");
        assertEquals("920066", page.getRows().get(0).getCode());
        assertEquals(0, page.getRows().get(0).getMarket().intValue());
    }

    /** 上游 total 是全市场 5909，和过滤后的行数不是一回事：两个数都要留着才对得上账。 */
    @Test
    void keepsUpstreamTotalAlongsideFilteredListSize() {
        StockListResult page = client.parseStockPage(Fixtures.text("stock_list.json"));

        assertEquals(5909, page.getTotal());
        assertFalse(page.isTruncated());
    }

    @Test
    void treatsNullStockListBodyAsFailureNotEmptyList() {
        StockListResult page = client.parseStockPage(null);

        assertFalse(page.isOk());
        assertTrue(page.getRows().isEmpty());
    }

    // ---------- 概念板块（D2 题材索引） ----------

    /** 概念板块清单：读 total 与每个板块的 BK 代码 + 名称。 */
    @Test
    void readsConceptBoardListWithCodeAndName() {
        ConceptBoardsPage page = client.parseConceptBoards(Fixtures.text("concept_boards.json"));

        assertTrue(page.isOk());
        assertEquals(504, page.getTotal());
        assertEquals(3, page.getRows().size());
        assertEquals("BK0976", page.getRows().get(0).getCode());
        assertEquals("被动元件概念", page.getRows().get(0).getName());
    }

    /** 概念板块成分股：读股票代码 + 简称，纯度比行业池高，不用白名单过滤。 */
    @Test
    void readsConceptMembersAsStockRows() {
        ConceptMembers members = client.parseConceptMembers(Fixtures.text("concept_members.json"));

        assertTrue(members.isOk());
        assertEquals(30, members.getTotal());
        assertEquals(3, members.getRows().size());
        assertEquals("002848", members.getRows().get(0).getCode());
        assertEquals("高斯贝尔", members.getRows().get(0).getName());
    }

    @Test
    void treatsNullConceptBoardBodyAsFailure() {
        ConceptBoardsPage page = client.parseConceptBoards(null);

        assertFalse(page.isOk());
        assertEquals("行情源无响应", page.getReason());
        assertTrue(page.getRows().isEmpty());
    }

    @Test
    void treatsNullConceptMemberBodyAsFailure() {
        ConceptMembers members = client.parseConceptMembers(null);

        assertFalse(members.isOk());
        assertTrue(members.getRows().isEmpty());
    }

    // ---------- 异动监管公告 ----------

    /**
     * 哈药 600664 的实测名单：五起异动，其中两起"严重"。
     * 08-21 那起正是使用者判断「退潮一阶段」的依据，判错类别整条线索就废了。
     */
    @Test
    void classifiesHarbinPharmaAbnormalMovesBySeverity() {
        SurveillanceResult result = client.parseSurveillance(Fixtures.text("ann_600664.json"), "600664");

        assertTrue(result.isOk());
        assertEquals(19, result.getRows());
        assertEquals(5, result.getNotices().size());
        assertEquals("哈药股份", result.getNotices().get(0).getName());
        assertEquals(SurveillanceKind.ZD, kindOn(result, "2026-07-14"));
        assertEquals(SurveillanceKind.ZD, kindOn(result, "2026-07-17"));
        assertEquals(SurveillanceKind.SEVERE, kindOn(result, "2026-07-24"));
        assertEquals(SurveillanceKind.ZD, kindOn(result, "2026-08-12"));
        assertEquals(SurveillanceKind.SEVERE, kindOn(result, "2026-08-21"));
    }

    /**
     * 一份窗口里 14 条都不是异动：半年报、董事会决议、风险提示、控股方的询证函回复。
     * 询证函回复标题里就带"异常波动"四个字，按标题判会凭空多起事件、还会把同一日算两遍，
     * 所以判据只能是类目码——连带那个"标题像信号却没有类目码"的哨兵也不能被它误触。
     */
    @Test
    void ignoresEverythingThatIsNotAnExchangeAction() {
        SurveillanceResult result = client.parseSurveillance(Fixtures.text("ann_600664.json"), "600664");

        assertEquals(0, result.getUnmatchedSignals());
        for (SurveillanceNotice notice : result.getNotices()) {
            assertEquals("001002004007", notice.getColumnCode());
        }
    }

    /** 连板途中的多日升级链：09-02 异常波动 → 09-03 交易所监管工作函 → 09-04 又异常波动 + 监管警示。 */
    @Test
    void readsExchangeWatchAsItsOwnKind() {
        SurveillanceResult result = client.parseSurveillance(Fixtures.text("ann_605577.json"), "605577");

        assertTrue(result.isOk());
        assertEquals(15, result.getRows());
        assertEquals(SurveillanceKind.ZD, kindOn(result, "2026-09-02"));
        assertEquals(SurveillanceKind.EXCH, kindOn(result, "2026-09-03"));
        // 09-04 同一天两起：公司自己的异常波动 + 交易所「予以监管警示的决定」
        assertEquals(2, countOn(result, "2026-09-04"));
        assertEquals(0, countOn(result, "2026-09-05"));
    }

    /** 交易所监管这一族靠类目码前缀认（010002009xxx），别和 001002009「董事会决议公告」混。 */
    @Test
    void keepsExchangeWatchColumnForAudit() {
        SurveillanceResult result = client.parseSurveillance(Fixtures.text("ann_605577.json"), "605577");

        assertEquals("010002009005", columnOn(result, "2026-09-03"));
        assertEquals("010002009001", columnOn(result, "2026-09-04"));
    }

    @Test
    void treatsNullAnnouncementBodyAsFailureNotEmptyWindow() {
        SurveillanceResult result = client.parseSurveillance(null, "600664");

        assertFalse(result.isOk());
        assertTrue(result.getNotices().isEmpty());
        assertEquals("公告源无响应", result.getReason());
    }

    private static SurveillanceKind kindOn(SurveillanceResult result, String date) {
        for (SurveillanceNotice notice : result.getNotices()) {
            if (date.equals(notice.getAnnDate().toString())) {
                return notice.getKind();
            }
        }
        return null;
    }

    private static String columnOn(SurveillanceResult result, String date) {
        for (SurveillanceNotice notice : result.getNotices()) {
            if (date.equals(notice.getAnnDate().toString())) {
                return notice.getColumnCode();
            }
        }
        return null;
    }

    private static int countOn(SurveillanceResult result, String date) {
        int count = 0;
        for (SurveillanceNotice notice : result.getNotices()) {
            if (date.equals(notice.getAnnDate().toString())) {
                count++;
            }
        }
        return count;
    }
}
