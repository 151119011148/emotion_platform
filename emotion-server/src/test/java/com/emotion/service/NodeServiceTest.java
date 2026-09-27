package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.emotion.entity.CandidateStock;
import com.emotion.entity.NodeEvent;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.NodeEventMapper;
import com.emotion.vo.NodeTagVO;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final Long USER = 2L;

    /** lambda 列名要查 TableInfo 缓存，没有 Spring 就得自己把这张表的元数据灌进去。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NodeEvent.class);
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
     * D0 候选现在要按日子撞，所以得有「上一个有明细的交易日」。
     * 这里不连库，给一张写死的表：键是候选行那天，值是它的上一交易日。
     */
    private static MarketStockMapper stockMapper(Map<LocalDate, LocalDate> prevOf) {
        return (MarketStockMapper) Proxy.newProxyInstance(
                MarketStockMapper.class.getClassLoader(),
                new Class<?>[]{MarketStockMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("prevDetailDate".equals(name)) {
                        return prevOf.get((LocalDate) args[0]);
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

    private static final Map<LocalDate, LocalDate> PREV = new HashMap<LocalDate, LocalDate>() {{
        put(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 4));
        put(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 17));
        // 明细缺一天：09-22 那天没有涨停池，它的上一个有明细的日子直接跳到 09-18
        put(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 18));
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
     * 名称比对要去空白（明细里是「罗 牛 山」、d0_candidates 里是「罗牛山」）；
     * 同一只票命中多种角色时全部保留——一只票今天是甲节点的节点票、
     * 明天又进了乙节点的 D0 名单，是常事。
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
        node7.setD0Candidates("[\"罗牛山\"]");
        node7.setD0Date(LocalDate.of(2026, 9, 17));
        node7.setStatus("待验证");

        NodeService svc = new NodeService(eventMapper(Arrays.asList(node1, node7)),
                null, null, stockMapper(PREV), null, null, null, new ObjectMapper());

        LocalDate day = LocalDate.of(2026, 9, 18);
        CandidateStock byNodeStock = candidate("600865", "百大集团", day);
        CandidateStock byAnchor = candidate("000993", "闽东电力", day);
        CandidateStock byD0 = candidate("000735", "罗 牛 山", day);
        CandidateStock none = candidate("300001", "特锐德", day);
        // 一只票同时是某节点的节点票、又是它的锚定龙头：少见，但匹配是按字段各自进行的
        CandidateStock both = candidate("600865", "国芳集团", day);
        // 在 node1 的 D0 名单里，但那天离 09-04 已经隔了十来个交易日：过期标不该再挂
        CandidateStock staleD0 = candidate("600892", "龙版传媒", day);

        svc.tagCandidates(Arrays.asList(byNodeStock, byAnchor, byD0, none, both, staleD0), USER);

        assertEquals(Arrays.asList("NODE_STOCK"), kinds(byNodeStock), "节点票该被认出来");
        assertEquals(Arrays.asList("ANCHOR"), kinds(byAnchor), "锚定龙头按名称匹配");
        assertEquals(Arrays.asList("D0_CAND"), kinds(byD0), "D0 候选要去空白后匹配");
        assertEquals(0, kinds(none).size(), "不在任何节点事件里的票不该被打标");
        assertEquals(Arrays.asList("NODE_STOCK", "ANCHOR"), kinds(both),
                "同一只票命中多种角色时要全部保留，不能只留第一条");
        assertEquals(0, kinds(staleD0).size(), "D0 名单只对它的次一交易日出标");
    }

    /**
     * D0 候选的日期闸门。
     *
     * <p>「上一个有明细的交易日」用的是明细本身，不是日历：09-22 缺涨停池记录时，
     * 它的上一有明细日子直接跳到 09-18，于是 09-22 的行撞的是 09-18 那批名单。
     * 名单本来就是从这份明细拉的，两边必须同一口径。
     */
    @Test
    void d0CandidateTagOnlyAppliesToTheDayAfterD0() {
        NodeEvent node = new NodeEvent();
        node.setId(9L);
        node.setD0Date(LocalDate.of(2026, 9, 17));
        node.setD0Candidates("[\"世联行\"]");

        NodeService svc = new NodeService(eventMapper(Arrays.asList(node)),
                null, null, stockMapper(PREV), null, null, null, new ObjectMapper());

        CandidateStock nextDay = candidate("002285", "世联行", LocalDate.of(2026, 9, 18));
        CandidateStock laterDay = candidate("002285", "世联行", LocalDate.of(2026, 9, 22));
        svc.tagCandidates(Arrays.asList(nextDay, laterDay), USER);

        assertEquals(Arrays.asList("D0_CAND"), kinds(nextDay), "D0 的次一交易日该挂标");
        assertEquals(0, kinds(laterDay).size(), "往后第二天起就不是这份名单的接力日了");
    }

    /** 一条节点事件都没有时（新用户），不该给候选行留下空的 nodeTags。 */
    @Test
    void tagCandidatesLeavesNoTagsWhenNoEvents() {
        NodeService svc = new NodeService(eventMapper(new ArrayList<NodeEvent>()),
                null, null, null, null, null, null, new ObjectMapper());
        CandidateStock c = candidate("600865", "百大集团", LocalDate.of(2026, 9, 18));
        svc.tagCandidates(Arrays.asList(c), USER);
        assertEquals(0, kinds(c).size());
    }

    private static Wrapper<NodeEvent> historyWrapper() {
        CAUGHT.clear();
        new NodeService(capturingMapper(), null, null, null, null, null, null, new ObjectMapper()).listByUser(USER);
        assertEquals(1, CAUGHT.size(), "listByUser 应该只发一次查询");
        return CAUGHT.get(0);
    }

    private static Wrapper<NodeEvent> currentWrapper() {
        CAUGHT.clear();
        new NodeService(capturingMapper(), null, null, null, null, null, null, new ObjectMapper()).getCurrent(USER);
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
    void historyStillFiltersToThisUser() {
        String sql = historyWrapper().getSqlSegment();
        assertTrue(sql.contains("user_id = "), sql);
        assertTrue(sql.indexOf("user_id") < sql.indexOf("ORDER BY"), "过滤该在排序之前：" + sql);
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
