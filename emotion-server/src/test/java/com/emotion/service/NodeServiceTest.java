package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.emotion.entity.NodeEvent;
import com.emotion.mapper.NodeEventMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

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

    private static Wrapper<NodeEvent> historyWrapper() {
        CAUGHT.clear();
        new NodeService(capturingMapper(), null, null, null, null, null, null).listByUser(USER);
        assertEquals(1, CAUGHT.size(), "listByUser 应该只发一次查询");
        return CAUGHT.get(0);
    }

    private static Wrapper<NodeEvent> currentWrapper() {
        CAUGHT.clear();
        new NodeService(capturingMapper(), null, null, null, null, null, null).getCurrent(USER);
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
