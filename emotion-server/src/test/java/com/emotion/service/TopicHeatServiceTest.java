package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emotion.entity.CandidateStock;
import com.emotion.mapper.StockConceptMapper;
import com.emotion.vo.ThemeTagVO;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 候选池打「通达信题材」的标。
 *
 * <p>这里没有库可查，能钉两件事：service 有没有按 code 正确分组（扁平行 → 每票一串）、
 * 以及交给 mapper 的 SQL 有没有带上「热度排序」与「LEFT JOIN 而不是内连接」。
 */
class TopicHeatServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    /** 记录被查过的参数：用来确认「没有候选时不该打库」和「重复代码要合并」。 */
    private static final List<List<String>> ASKED = new ArrayList<List<String>>();

    private static StockConceptMapper mapperReturning(List<ThemeTagVO> flat) {
        return (StockConceptMapper) Proxy.newProxyInstance(
                StockConceptMapper.class.getClassLoader(),
                new Class<?>[]{StockConceptMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("listThemesByCodes".equals(name)) {
                        ASKED.add((List<String>) args[0]);
                        return flat;
                    }
                    if ("toString".equals(name)) {
                        return "themeMapper";
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

    private static ThemeTagVO tag(String code, String name, int ztCount) {
        ThemeTagVO t = new ThemeTagVO();
        t.setCode(code);
        t.setName(name);
        t.setFullName(name);
        t.setZtCount(ztCount);
        return t;
    }

    private static CandidateStock candidate(String code, String name) {
        CandidateStock c = new CandidateStock();
        c.setCode(code);
        c.setName(name);
        return c;
    }

    private static List<String> names(CandidateStock c) {
        List<String> out = new ArrayList<String>();
        if (c.getTdxThemes() == null) {
            return out;
        }
        for (ThemeTagVO t : c.getTdxThemes()) {
            out.add(t.getName());
        }
        return out;
    }

    /**
     * 扁平行要按 code 归到各自那行，且<strong>保持 SQL 给的热度顺序</strong>。
     *
     * <p>顺序这一条容易在「顺手再排一次」时丢掉：SQL 已经按 (code, 涨停家数 DESC) 排好，
     * service 若改用 HashMap 装桶或再按名称排一次，界面首屏显示的就不是最热的题材了。
     */
    @Test
    void groupsFlatRowsByCodeAndKeepsHeatOrder() {
        ASKED.clear();
        TopicHeatService svc = new TopicHeatService(mapperReturning(Arrays.asList(
                tag("001216", "消费电子", 15),
                tag("001216", "跨境电商", 8),
                tag("600630", "新零售", 15),
                tag("600630", "物业管理", 10))));

        CandidateStock a = candidate("001216", "华瓷股份");
        CandidateStock b = candidate("600630", "龙头股份");
        // 库里没有它的题材记录：要给空列表，不是 null——界面据此回退到行业
        CandidateStock none = candidate("999999", "查无此票");

        svc.tagTdxThemes(Arrays.asList(a, b, none), DAY);

        assertEquals(Arrays.asList("消费电子", "跨境电商"), names(a), "顺序要跟 SQL 一致（热度降序）");
        assertEquals(Arrays.asList("新零售", "物业管理"), names(b));
        assertNotNull(none.getTdxThemes(), "取不到题材时给空列表而不是 null");
        assertEquals(0, names(none).size());
        assertEquals(1, ASKED.size(), "三只票只该查一次库");
        assertEquals(3, ASKED.get(0).size(), "重复代码要合并，不能重复进 IN");
    }

    /** 候选为空 / 日期为空（还没定交易日）时不该打库。 */
    @Test
    void doesNotQueryWhenNothingToTag() {
        ASKED.clear();
        TopicHeatService svc = new TopicHeatService(mapperReturning(new ArrayList<ThemeTagVO>()));
        svc.tagTdxThemes(new ArrayList<CandidateStock>(), DAY);
        svc.tagTdxThemes(null, DAY);
        svc.tagTdxThemes(Arrays.asList(candidate("001216", "华瓷股份")), null);
        assertEquals(0, ASKED.size(), "没有候选或没有日期时不该发查询");
    }

    /** 同一只票重复出现（不该发生，但真发生时）只进一次 IN，别让 SQL 里出现重复参数。 */
    @Test
    void dedupesCodes() {
        ASKED.clear();
        TopicHeatService svc = new TopicHeatService(mapperReturning(new ArrayList<ThemeTagVO>()));
        svc.tagTdxThemes(Arrays.asList(candidate("001216", "华瓷股份"), candidate("001216", "华瓷股份")), DAY);
        assertEquals(Arrays.asList("001216"), ASKED.get(0));
    }

    /**
     * 钉住那条查询本身：热度子查询必须是 {@code LEFT JOIN}。
     *
     * <p>改成内连接会静默出两种错：当日该题材一只票都没涨停时整条映射消失
     * （表现成「这只票没题材」），以及当日只有它自己涨停时整条映射也消失。
     * 这两种都很难在界面上看出来——只会觉得「题材列有时候是空的」。
     */
    @Test
    void themeQueryKeepsLeftJoinAndHeatOrder() throws Exception {
        Method m = StockConceptMapper.class.getMethod("listThemesByCodes", List.class, LocalDate.class);
        Select select = m.getAnnotation(Select.class);
        assertNotNull(select, "listThemesByCodes 该是注解式 SQL");
        String sql = String.join("", select.value());

        assertTrue(sql.contains("LEFT JOIN"), "热度要用 LEFT JOIN，内连接会丢票：" + sql);
        assertTrue(!sql.contains("JOIN t_market_stock s ON s.code = c.code"),
                "主表不该直接内连接涨停池：" + sql);
        assertTrue(sql.indexOf("ORDER BY") > 0 && sql.contains("ztCount DESC"),
                "题材必须按当日涨停家数降序下发：" + sql);
        assertTrue(sql.contains("t_concept_board"), "要带上板块指数代码，便于对照海王星：" + sql);
    }
}
