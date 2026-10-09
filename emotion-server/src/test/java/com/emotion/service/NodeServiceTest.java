package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.emotion.entity.CandidateStock;
import com.emotion.entity.MarketStock;
import com.emotion.entity.NodeEvent;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.NodeEventMapper;
import com.emotion.vo.NodeTagVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点历史那一张表的顺序。
 *
 * <p>这里没有库可查，能钉的是 service 交给 mapper 的那段 SQL：过滤条件还在、
 * <b>排序第一键是 {@code d0_date DESC} 而不是 {@code created_at DESC}</b>。
 * 「D0 为空的排最后」这一半是 MySQL 的 DESC 语义（NULL 最小，倒排自然落尾），
 * 由端到端那一步在真库上核。
 */
class NodeServiceTest {


    /** lambda 列名要查 TableInfo 缓存，没有 Spring 就得自己把这张表的元数据灌进去。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NodeEvent.class);
        // 打标现在要按明细复算 D0 的二板池，t_market_stock 的 lambda 列名也得能解析
        TableInfoHelper.initTableInfo(assistant, MarketStock.class);
    }

    /** 只记不查：拿到 service 递下来的 wrapper 就够了。 */
    private static final List<Wrapper<NodeEvent>> CAUGHT = new ArrayList<>();

    private static NodeEventMapper capturingMapper() {
        return (NodeEventMapper) Proxy.newProxyInstance(
                NodeEventMapper.class.getClassLoader(),
                new Class<?>[]{NodeEventMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("selectList".equals(name) || "selectOne".equals(name)) {
                        CAUGHT.add((Wrapper<NodeEvent>) args[0]);
                        return "selectList".equals(name) ? new ArrayList<NodeEvent>() : null;
                    }
                    // Object 那三个也要有返回值：hashCode 拆箱 null 会直接 NPE
                    if ("toString".equals(name)) {
                        return "capturingNodeEventMapper";
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    return null;
                });
    }

    /** 返回固定事件列表的 mapper：tagCandidates 是纯匹配，不需要库。 */
    private static NodeEventMapper eventMapper(List<NodeEvent> events) {
        return (NodeEventMapper) Proxy.newProxyInstance(
                NodeEventMapper.class.getClassLoader(),
                new Class<?>[]{NodeEventMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return events;
                    }
                    if ("toString".equals(method.getName())) {
                        return "eventMapper";
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    return null;
                });
    }

    /**
     * D0 候选现在要按日子撞，所以得有「上一个有明细的交易日」；池子又要按明细复算，
     * 所以还得有当天的涨停行。这里不连库：前者给一张写死的表（键是候选行那天），
     * 后者把「查询本该命中的行」直接递进桩——桩不重放 SQL 条件，别指望它过滤板数。
     */
    private static MarketStockMapper stockMapper(Map<LocalDate, LocalDate> prevOf, MarketStock... detail) {
        final List<MarketStock> rows = Arrays.asList(detail);
        return (MarketStockMapper) Proxy.newProxyInstance(
                MarketStockMapper.class.getClassLoader(),
                new Class<?>[]{MarketStockMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("prevDetailDate".equals(name)) {
                        return prevOf.get((LocalDate) args[0]);
                    }
                    if ("selectList".equals(name)) {
                        return new ArrayList<MarketStock>(rows);
                    }
                    if ("toString".equals(name)) {
                        return "stockMapper";
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    return null;
                });
    }

    /** D0 当天涨停池里的一条二板。 */
    private static MarketStock twoBoard(LocalDate date, String code, String name) {
        MarketStock m = new MarketStock();
        m.setTradeDate(date);
        m.setCode(code);
        m.setName(name);
        m.setPool(MarketStock.POOL_LIMIT_UP);
        m.setConsecutive(NodeSuggestService.A_CANDIDATE_BOARD);
        return m;
    }

    private static final Map<LocalDate, LocalDate> PREV = new HashMap<LocalDate, LocalDate>() {{
        put(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 4));
        put(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 17));
        // 明细缺一天：09-22 那天没有涨停池，它的上一个有明细的日子直接跳到 09-18
        put(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 18));
    }};

    /** 节点加分要往回数 N 个「有明细的日子」，这条链就是那把尺子：09-19/09-20 没有明细。 */
    private static final Map<LocalDate, LocalDate> WINDOW = new HashMap<LocalDate, LocalDate>() {{
        put(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 18));
        put(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 17));
        put(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 16));
        put(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 15));
    }};

    private static CandidateStock candidate(String code, String name, LocalDate tradeDate) {
        CandidateStock c = new CandidateStock();
        c.setCode(code);
        c.setName(name);
        c.setTradeDate(tradeDate);
        return c;
    }

    private static List<String> kinds(CandidateStock c) {
        List<String> out = new ArrayList<String>();
        if (c.getNodeTags() == null) {
            return out;
        }
        for (NodeTagVO t : c.getNodeTags()) {
            out.add(t.getKind());
        }
        return out;
    }

    /**
     * 候选池打「来自节点追踪」的标。
     *
     * <p>钉三件容易写错的事：节点票靠「名称(代码)」里的代码匹配，不是靠名称；
     * D0 候选认的是<b>那天明细里的二板</b>，不是手打的 {@code d0_candidates} 名单——
     * 名单里有但它不是二板 → 不标，它确实是二板但名单里没写 → 照标；
     * 同一只票命中多种角色时全部保留——一只票今天是甲节点的节点票、
     * 明天又进了乙节点的 D0 池子，是常事。
     */
    @Test
    void tagCandidatesMatchesEveryRole() {
        NodeEvent node1 = new NodeEvent();
        node1.setId(1L);
        node1.setNodeStock("百大集团(600865)");
        node1.setAnchorStock("国芳集团");
        node1.setD0Candidates("[\"龙版传媒\",\"亚盛集团\"]");
        node1.setD0Date(LocalDate.of(2026, 9, 4));
        node1.setStatus("失效");

        NodeEvent node7 = new NodeEvent();
        node7.setId(7L);
        node7.setNodeStock("世联行(002285)");
        node7.setAnchorStock("闽东电力");
        // 名单里只有罗牛山：复算口径下它不是 09-17 的二板，就不该再挂标
        node7.setD0Candidates("[\"罗牛山\"]");
        node7.setD0Date(LocalDate.of(2026, 9, 17));
        node7.setStatus("待验证");

        LocalDate d0 = LocalDate.of(2026, 9, 17);
        NodeService svc = new NodeService(eventMapper(Arrays.asList(node1, node7)),
                null, null, stockMapper(PREV,
                        twoBoard(d0, "002285", "世联行"),
                        twoBoard(d0, "000931", "中 关 村")),
                null, null, null);

        LocalDate day = LocalDate.of(2026, 9, 18);
        CandidateStock byNodeStock = candidate("600865", "百大集团", day);
        CandidateStock byAnchor = candidate("000993", "闽东电力", day);
        // 二板池里有它、手打名单里没写：复算口径要认
        CandidateStock poolNotRoster = candidate("000931", "中 关 村", day);
        // 手打名单里有它、当天不是二板：复算口径不认
        CandidateStock rosterNotPool = candidate("000735", "罗牛山", day);
        CandidateStock none = candidate("300001", "特锐德", day);
        // 一只票同时是某节点的节点票、又是它的锚定龙头：少见，但匹配是按字段各自进行的
        CandidateStock both = candidate("600865", "国芳集团", day);
        // 在 node1 的 D0 名单里，但那天离 09-04 已经隔了十来个交易日：过期标不该再挂
        CandidateStock staleD0 = candidate("600892", "龙版传媒", day);

        svc.tagCandidates(Arrays.asList(byNodeStock, byAnchor, poolNotRoster, rosterNotPool,
                none, both, staleD0));

        assertEquals(Arrays.asList("NODE_STOCK"), kinds(byNodeStock), "节点票该被认出来");
        assertEquals(Arrays.asList("ANCHOR"), kinds(byAnchor), "锚定龙头按名称匹配");
        assertEquals(Arrays.asList("D0_CAND"), kinds(poolNotRoster),
                "D0 当天的二板就该挂标，名单里写没写都一样；名称带空格也按代码认");
        assertEquals(0, kinds(rosterNotPool).size(),
                "手打名单里的人不是那天的二板 → 不挂标，判据数的是二板池");
        assertEquals(0, kinds(none).size(), "不在任何节点事件里的票不该被打标");
        assertEquals(Arrays.asList("NODE_STOCK", "ANCHOR"), kinds(both),
                "同一只票命中多种角色时要全部保留，不能只留第一条");
        assertEquals(0, kinds(staleD0).size(), "D0 池子只对它的次一交易日出标");
    }

    /**
     * D0 候选的日期闸门。
     *
     * <p>「上一个有明细的交易日」用的是明细本身，不是日历：09-22 缺涨停池记录时，
     * 它的上一有明细日子直接跳到 09-18，于是 09-22 的行撞的是 09-18 那批池子。
     * 池子本来就是从这份明细拉的，两边必须同一口径。
     */
    @Test
    void d0CandidateTagOnlyAppliesToTheDayAfterD0() {
        NodeEvent node = new NodeEvent();
        node.setId(9L);
        node.setD0Date(LocalDate.of(2026, 9, 17));
        node.setD0Candidates("[\"世联行\"]");

        NodeService svc = new NodeService(eventMapper(Arrays.asList(node)),
                null, null, stockMapper(PREV,
                        twoBoard(LocalDate.of(2026, 9, 17), "002285", "世联行")),
                null, null, null);

        CandidateStock nextDay = candidate("002285", "世联行", LocalDate.of(2026, 9, 18));
        CandidateStock laterDay = candidate("002285", "世联行", LocalDate.of(2026, 9, 22));
        svc.tagCandidates(Arrays.asList(nextDay, laterDay));

        assertEquals(Arrays.asList("D0_CAND"), kinds(nextDay), "D0 的次一交易日该挂标");
        assertEquals(0, kinds(laterDay).size(), "往后第二天起就不是这份池子的接力日了");
    }

    /**
     * 两条复算才会出现的分支：D0 那天没有明细就没有池子，B 类事件的池子是板块首板、
     * 不是全市场二板，两者都不该给出 A 口径的标。
     */
    @Test
    void d0CandidateTagNeedsARecomputedPool() {
        NodeEvent noDetail = new NodeEvent();
        noDetail.setId(10L);
        noDetail.setD0Date(LocalDate.of(2026, 9, 17));
        noDetail.setD0Candidates("[\"世联行\"]");

        NodeEvent systemB = new NodeEvent();
        systemB.setId(11L);
        systemB.setSystemType("B");
        systemB.setD0Date(LocalDate.of(2026, 9, 17));
        systemB.setD0Candidates("[\"世联行\"]");

        // 一个 D0 都没有明细：名单照旧写着，标一个都不挂
        NodeService empty = new NodeService(eventMapper(Arrays.asList(noDetail)),
                null, null, stockMapper(PREV), null, null, null);
        CandidateStock noPool = candidate("002285", "世联行", LocalDate.of(2026, 9, 18));
        empty.tagCandidates(Arrays.asList(noPool));
        assertEquals(0, kinds(noPool).size(), "D0 那天没明细 → 池子无从复算，别拿手打名单兜底");

        NodeService b = new NodeService(eventMapper(Arrays.asList(systemB)),
                null, null, stockMapper(PREV,
                        twoBoard(LocalDate.of(2026, 9, 17), "002285", "世联行")),
                null, null, null);
        CandidateStock bRow = candidate("002285", "世联行", LocalDate.of(2026, 9, 18));
        b.tagCandidates(Arrays.asList(bRow));
        assertEquals(0, kinds(bRow).size(), "B 的池子是板块内首板，不能拿全市场二板挂标");
    }

    /** 一条节点事件都没有时（新用户），不该给候选行留下空的 nodeTags。 */
    @Test
    void tagCandidatesLeavesNoTagsWhenNoEvents() {
        NodeService svc = new NodeService(eventMapper(new ArrayList<NodeEvent>()),
                null, null, null, null, null, null);
        CandidateStock c = candidate("600865", "百大集团", LocalDate.of(2026, 9, 18));
        svc.tagCandidates(Arrays.asList(c));
        assertEquals(0, kinds(c).size());
    }

    /**
     * 节点加分的四道闸，一道都不许松，因为这一项要动仓位：
     * 窗口内的未失效节点、有 D0、节点票解得出代码，四条各占一样。
     */
    @Test
    void scoredNodeStocksKeepsOnlyLiveNodesInsideTheWindow() {
        NodeEvent inWindow = node(11L, "内蒙新华(603230)", LocalDate.of(2026, 9, 17), "待验证");
        NodeEvent tooOld = node(12L, "国芳集团(601086)", LocalDate.of(2026, 8, 31), "有效");
        NodeEvent dead = node(13L, "百大集团(600865)", LocalDate.of(2026, 9, 18), "失效");
        NodeEvent noD0 = node(14L, "桂林旅游(000978)", null, "有效");
        // 节点票只写了名称、没带(代码)：解不出代码就不认，宁可少给分也不按名称乱撞
        NodeEvent noCode = node(15L, "天威视讯", LocalDate.of(2026, 9, 21), "有效");

        NodeService svc = new NodeService(
                eventMapper(Arrays.asList(inWindow, tooOld, dead, noD0, noCode)),
                null, null, stockMapper(WINDOW), null, null, null);

        Map<String, NodeEvent> got = svc.scoredNodeStocks(LocalDate.of(2026, 9, 21), 3);

        assertEquals(Arrays.asList("603230"), new ArrayList<>(got.keySet()),
                "09-21 往前数 3 个有明细的日子是 09-21/09-18/09-17，只有这条 D0 撞得上");
        assertEquals(inWindow, got.get("603230"));
    }

    /** 同一只票挂在多条节点下时留 D0 最近的那条：加分看的是它现在属于哪个节点，多条叠乘会把 0.2 撑成 0.4。 */
    @Test
    void scoredNodeStocksKeepsTheNearestNodePerStock() {
        NodeEvent older = node(21L, "内蒙新华(603230)", LocalDate.of(2026, 9, 18), "有效");
        NodeEvent newer = node(22L, "内蒙新华(603230)", LocalDate.of(2026, 9, 21), "有效");

        NodeService svc = new NodeService(eventMapper(Arrays.asList(older, newer)),
                null, null, stockMapper(WINDOW), null, null, null);

        Map<String, NodeEvent> got = svc.scoredNodeStocks(LocalDate.of(2026, 9, 21), 3);

        assertEquals(1, got.size(), "同一只票只留一条");
        assertEquals(newer, got.get("603230"));
    }

    private static NodeEvent node(Long id, String nodeStock, LocalDate d0, String status) {
        NodeEvent e = new NodeEvent();
        e.setId(id);
        e.setNodeStock(nodeStock);
        e.setD0Date(d0);
        e.setStatus(status);
        return e;
    }

    private static Wrapper<NodeEvent> historyWrapper() {
        CAUGHT.clear();
        new NodeService(capturingMapper(), null, null, null, null, null, null).listAll();
        assertEquals(1, CAUGHT.size(), "listAll 应该只发一次查询");
        return CAUGHT.get(0);
    }

    private static Wrapper<NodeEvent> currentWrapper() {
        CAUGHT.clear();
        new NodeService(capturingMapper(), null, null, null, null, null, null).getCurrent();
        assertEquals(1, CAUGHT.size(), "getCurrent 应该只发一次查询");
        return CAUGHT.get(0);
    }

    private static String orderBy(Wrapper<NodeEvent> w) {
        int at = w.getSqlSegment().indexOf("ORDER BY");
        assertTrue(at >= 0, "这条查询没带排序：" + w.getSqlSegment());
        return w.getSqlSegment().substring(at).trim();
    }

    @Test
    void historyLeadsWithD0NotCreatedAt() {
        // 补录一个几个月前的节点，不该把它顶到第一行：他看的是"哪一天起的"
        assertEquals("ORDER BY d0_date DESC,created_at DESC", orderBy(historyWrapper()));
    }

    @Test
    void currentPendingUsesTheSameD0Order() {
        Wrapper<NodeEvent> w = currentWrapper();
        String sql = w.getSqlSegment();
        assertTrue(sql.contains("status = "), "当前节点还是要只挑待验证的那批：" + sql);
        // 尾上的 LIMIT 1 也在这一段里：排序改了，"只取一条"不能跟着丢
        assertEquals("ORDER BY d0_date DESC,created_at DESC LIMIT 1", orderBy(w));
    }
}
