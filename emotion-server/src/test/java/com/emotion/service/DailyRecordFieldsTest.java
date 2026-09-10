package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emotion.dto.DailyRecordRequest;
import com.emotion.entity.DailyRecord;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 表单那一串键到库里那一格的对应关系，重点只有一件事：<b>「这格没发键」必须等于「这格不动」</b>。
 *
 * <p>为什么钉这么细：二十一份 ALWAYS 策略的列（涨跌家数、我的仓位、对照、旧九维八格 manual_*、
 * 五维九格 manual_*）
 * 在 update 时是"给什么写什么"，包括写 NULL。复盘页上删掉一块、或者一个只改阶段的半截请求，
 * 只要还按"值非 null 才写"的老规矩发，就会把 md 导入存进去的数一并洗成空。
 * 那种洗法界面上看不见，事后也追不回来。
 *
 * <p>走的是 {@link DailyRecordService#copyFields} 这个纯函数，加上一个和控制器同一套配置的
 * ObjectMapper（Boot 的 builder 默认关掉 FAIL_ON_UNKNOWN_PROPERTIES，这里跟着关），
 * 于是键名拼错也能被抓住——键名是 JSON 上的字面量，编译器管不着。
 */
class DailyRecordFieldsTest {

    /** 和控制器同一份键名清单：这里的字符串写错，下面三条路立刻对不上。 */
    private static final class Column {
        final String key;
        final Object stored;
        final Object sent;
        final Function<DailyRecord, Object> get;
        final BiConsumer<DailyRecord, Object> set;

        Column(String key, Object stored, Object sent,
               Function<DailyRecord, Object> get, BiConsumer<DailyRecord, Object> set) {
            this.key = key;
            this.stored = stored;
            this.sent = sent;
            this.get = get;
            this.set = set;
        }
    }

    private static final Column[] ALWAYS = {
            new Column("upCount", 1846, 46,
                    DailyRecord::getUpCount, (r, v) -> r.setUpCount((Integer) v)),
            new Column("downCount", 3570, 210,
                    DailyRecord::getDownCount, (r, v) -> r.setDownCount((Integer) v)),
            new Column("myPositionPct", new BigDecimal("55"), new BigDecimal("30"),
                    DailyRecord::getMyPositionPct, (r, v) -> r.setMyPositionPct((BigDecimal) v)),
            new Column("compareNote", "旧对照", "新对照",
                    DailyRecord::getCompareNote, (r, v) -> r.setCompareNote((String) v)),
            new Column("manualSealedHomeRate", new BigDecimal("66.6"), new BigDecimal("88.8"),
                    DailyRecord::getManualSealedHomeRate, (r, v) -> r.setManualSealedHomeRate((BigDecimal) v)),
            new Column("manualResealRate", new BigDecimal("40.0"), new BigDecimal("55.5"),
                    DailyRecord::getManualResealRate, (r, v) -> r.setManualResealRate((BigDecimal) v)),
            new Column("manualPremiumLowPct", new BigDecimal("1.2"), new BigDecimal("2.3"),
                    DailyRecord::getManualPremiumLowPct, (r, v) -> r.setManualPremiumLowPct((BigDecimal) v)),
            new Column("manualPremiumMidPct", new BigDecimal("2.4"), new BigDecimal("3.5"),
                    DailyRecord::getManualPremiumMidPct, (r, v) -> r.setManualPremiumMidPct((BigDecimal) v)),
            new Column("manualPremiumHighPct", new BigDecimal("4.8"), new BigDecimal("5.9"),
                    DailyRecord::getManualPremiumHighPct, (r, v) -> r.setManualPremiumHighPct((BigDecimal) v)),
            new Column("manualAnchorScore", 2, -1,
                    DailyRecord::getManualAnchorScore, (r, v) -> r.setManualAnchorScore((Integer) v)),
            // sent = 0：0 是一个真的覆盖值，不是缺省。发它就得分寸不动地写进去。
            new Column("manualSurvCount", 5, 0,
                    DailyRecord::getManualSurvCount, (r, v) -> r.setManualSurvCount((Integer) v)),
            new Column("manualSurvPremium", new BigDecimal("-1.5"), new BigDecimal("9.9"),
                    DailyRecord::getManualSurvPremium, (r, v) -> r.setManualSurvPremium((BigDecimal) v)),
            // ===== 五维人工读数九格（Stage 10）：同样三条路（缺键不动 / 发 null 清空 / 发值落库）逐个钉 =====
            new Column("manualSectorLimitUpCount", 3, 5,
                    DailyRecord::getManualSectorLimitUpCount, (r, v) -> r.setManualSectorLimitUpCount((Integer) v)),
            new Column("manualLadderCompleteScore", new BigDecimal("90"), new BigDecimal("35"),
                    DailyRecord::getManualLadderCompleteScore, (r, v) -> r.setManualLadderCompleteScore((BigDecimal) v)),
            new Column("manualSectorPremiumPct", new BigDecimal("1.8"), new BigDecimal("-2.4"),
                    DailyRecord::getManualSectorPremiumPct, (r, v) -> r.setManualSectorPremiumPct((BigDecimal) v)),
            new Column("manualThemePersistenceDays", 1, 4,
                    DailyRecord::getManualThemePersistenceDays, (r, v) -> r.setManualThemePersistenceDays((Integer) v)),
            new Column("manualTopHighTurnoverPct", new BigDecimal("22.5"), new BigDecimal("41.0"),
                    DailyRecord::getManualTopHighTurnoverPct, (r, v) -> r.setManualTopHighTurnoverPct((BigDecimal) v)),
            new Column("manualFirstPremiumPct", new BigDecimal("3.3"), new BigDecimal("-1.1"),
                    DailyRecord::getManualFirstPremiumPct, (r, v) -> r.setManualFirstPremiumPct((BigDecimal) v)),
            new Column("manualFirstSealedRate", new BigDecimal("61.0"), new BigDecimal("85.0"),
                    DailyRecord::getManualFirstSealedRate, (r, v) -> r.setManualFirstSealedRate((BigDecimal) v)),
            // sent = 0：0 是「判过了，答案是没断板」，是一个真读数不是缺省，必须原样落库。
            new Column("manualTopHighBreak", 1, 0,
                    DailyRecord::getManualTopHighBreak, (r, v) -> r.setManualTopHighBreak((Integer) v)),
            new Column("manualAnchorSupervisionDiscount", new BigDecimal("1.00"), new BigDecimal("0.80"),
                    DailyRecord::getManualAnchorSupervisionDiscount,
                    (r, v) -> r.setManualAnchorSupervisionDiscount((BigDecimal) v)),
    };

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 走控制器那条路：原始 body → DTO + 键集合 → copyFields 改的是从库里读出来的那一行。 */
    private static DailyRecord apply(Map<String, Object> body, DailyRecord record) {
        DailyRecordRequest req = JSON.convertValue(body, DailyRecordRequest.class);
        DailyRecordService.copyFields(record, req, body.keySet());
        return record;
    }

    private static DailyRecord seeded(Column c) {
        DailyRecord r = new DailyRecord();
        c.set.accept(r, c.stored);
        return r;
    }

    /** 一份只发了行情读数的 body：这二十一格一个都没发。 */
    private static Map<String, Object> unrelatedBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("limitUpCount", 44);
        return body;
    }

    private static void assertEq(String what, Object expected, Object actual) {
        if (expected instanceof BigDecimal) {
            assertTrue(actual != null && ((BigDecimal) expected).compareTo((BigDecimal) actual) == 0,
                    what + " 应为 " + expected + "，实际 " + actual);
            return;
        }
        assertEquals(expected, actual, what);
    }

    @Test
    void absentKeyLeavesTheStoredValueAlone() {
        for (Column c : ALWAYS) {
            DailyRecord r = apply(unrelatedBody(), seeded(c));
            assertEq(c.key + " 没发键就不该动", c.stored, c.get.apply(r));
        }
    }

    @Test
    void explicitNullClearsTheCell() {
        for (Column c : ALWAYS) {
            DailyRecord r = apply(Collections.singletonMap(c.key, null), seeded(c));
            assertNull(c.get.apply(r), c.key + " 发了 null 是「这格清回未填」，得真的回 NULL");
        }
    }

    @Test
    void sentValueOverwritesTheCell() {
        for (Column c : ALWAYS) {
            DailyRecord r = apply(Collections.singletonMap(c.key, c.sent), seeded(c));
            assertEq(c.key + " 发了值就该落库", c.sent, c.get.apply(r));
        }
    }

    /** 一条整页提交：发出去的格子落库，没发出去的格子原样留着。 */
    @Test
    void partialSubmitWritesOnlyWhatItSent() {
        DailyRecord stored = new DailyRecord();
        stored.setUpCount(1846);
        stored.setDownCount(3570);
        stored.setMyPositionPct(new BigDecimal("55"));
        stored.setCompareNote("旧对照");
        stored.setManualAnchorScore(2);
        stored.setManualSurvPremium(new BigDecimal("-1.5"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("upCount", 46);
        body.put("compareNote", "新对照");
        body.put("manualSurvPremium", 9.9);
        DailyRecord r = apply(body, stored);

        assertEquals(Integer.valueOf(46), r.getUpCount());
        assertEquals("新对照", r.getCompareNote());
        assertEq("发的值要落库", new BigDecimal("9.9"), r.getManualSurvPremium());
        assertEquals(Integer.valueOf(3570), r.getDownCount(), "没发的下家数不许被洗掉");
        assertEq("没发的仓位不许被洗掉", new BigDecimal("55"), r.getMyPositionPct());
        assertEquals(Integer.valueOf(2), r.getManualAnchorScore(), "没发的覆盖格不许退回未覆盖");
    }

    /** 空串是"删空了这格"，和 null 同义；不带键才是"别动"。 */
    @Test
    void blankTextClearsButAbsentTextKeeps() {
        DailyRecord kept = apply(unrelatedBody(), seeded(ALWAYS[3]));
        assertEquals("旧对照", kept.getCompareNote());

        DailyRecord cleared = apply(Collections.singletonMap("compareNote", "   "), seeded(ALWAYS[3]));
        assertNull(cleared.getCompareNote(), "发一个空白串就是要把这格删干净");
    }

    /** 那七格行情读数是另一套：发 null 也不动，因为界面不允许把它清空、只有拉取会写。 */
    @Test
    void marketReadingsStayNullGuarded() {
        DailyRecord r = new DailyRecord();
        r.setMaxConsecutiveLimit(5);
        r.setLimitUpCount(44);

        Map<String, Object> body = new HashMap<>();
        body.put("maxConsecutiveLimit", null);
        apply(body, r);

        assertEquals(Integer.valueOf(5), r.getMaxConsecutiveLimit());
        assertEquals(Integer.valueOf(44), r.getLimitUpCount());
    }

    /** 主线/龙头那几串由 md 导入当作者：页面不发键、发 null 都不该动它。 */
    @Test
    void mdAuthoredStringsAreNotTouchedByAnEmptyForm() {
        DailyRecord r = new DailyRecord();
        r.setMainTheme("液冷服务器");
        r.setLeadingStock("国芳集团");
        r.setRotationNote("旧观察");
        r.setTomorrowPlan("旧计划");

        Map<String, Object> body = new HashMap<>();
        body.put("mainTheme", null);
        body.put("leadingStock", null);
        apply(body, r);

        assertEquals("液冷服务器", r.getMainTheme());
        assertEquals("国芳集团", r.getLeadingStock());
        assertEquals("旧观察", r.getRotationNote());
        assertEquals("旧计划", r.getTomorrowPlan());
    }

    /**
     * 多一个不认识的键不能把请求打成 500。
     * 前端换版本、浏览器拿着旧 bundle 发一个后端已删的键，是发布日常，不是异常。
     */
    @Test
    void unknownKeyIsIgnoredNotFatal() {
        DailyRecord r = apply(Collections.singletonMap("aKeyFromANewerBuild", "别理我"), new DailyRecord());
        assertNull(r.getUpCount());
    }
}
