package com.emotion.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code doc_notes} 那一列的 JSON 往返。这里的东西是手打的判断文字，全篇最贵，
 * 所以断言的重心只有一条：<b>任何情况下都不能把他的字弄丢</b>——
 * 解析不了要当成没有而不是炸，键名对不上要留着，一个字符都不改。
 */
class SectionNotesTest {

    @Test
    void roundTripKeepsTextVerbatimAndKeyOrder() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("theme", "液冷服务器 70 扩散\n（元宝：AI 算力链资金回流）  ");
        notes.put("anchor", "竞业达：连续三天跌停板未破");

        Map<String, String> back = SectionNotes.fromJson(SectionNotes.toJson(notes));

        assertEquals("液冷服务器 70 扩散\n（元宝：AI 算力链资金回流）  ", back.get("theme"));
        assertEquals("竞业达：连续三天跌停板未破", back.get("anchor"));
        assertEquals(Arrays.asList("theme", "anchor"), new ArrayList<>(back.keySet()));
    }

    /** 空格子不算填过：正文全空必须写成 null，否则 ALWAYS 策略只会把"填了个空表"存进去。 */
    @Test
    void allBlankCollapsesToNullSoTheColumnClears() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("theme", "   ");
        notes.put("plan", "");
        notes.put("anchor", null);

        assertNull(SectionNotes.toJson(notes));
        assertNull(SectionNotes.toJson(null));
        assertTrue(SectionNotes.fromJson(null).isEmpty());
    }

    /** 填过一格又清空另一格：只剩那一格，别的键不出现（不出现才会被渲染成占位行）。 */
    @Test
    void blankEntriesAreDroppedButItsSiblingSurvives() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("emotion", "涨停 22 跌停 45，情绪偏弱");
        notes.put("index", "  ");

        assertEquals(Collections.singletonList("emotion"),
                new ArrayList<>(SectionNotes.fromJson(SectionNotes.toJson(notes)).keySet()));
    }

    /** 库里手改坏、截断过、根本不是 JSON——一律按没有处理，不能让详情页因此 500。 */
    @Test
    void corruptColumnDegradesToEmptyInsteadOfThrowing() {
        assertTrue(SectionNotes.fromJson("{不是 JSON").isEmpty());
        assertTrue(SectionNotes.fromJson("[1,2,3]").isEmpty());
        assertTrue(SectionNotes.fromJson("   ").isEmpty());
    }

    /**
     * 对不上号的键要留住。小节哪天改了名，按键过滤等于把他写进"旧主线"那段静默删掉；
     * 留着，渲染器会把它单列一节露出来（见 ReviewDocFormatterTest.unregisteredKeyIsSurfacedNotDropped）。
     */
    @Test
    void unregisteredKeySurvivesTheRoundTrip() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("旧主线", "这段字找不着小节，但不能没");

        Map<String, String> json = SectionNotes.fromJson(SectionNotes.toJson(notes));

        assertEquals("这段字找不着小节，但不能没", json.get("旧主线"));
    }

    /** 已知键按 NOTE_KEYS 归序：导出的小节顺序由此决定，不能跟着前端发来的顺序漂。 */
    @Test
    void knownKeysComeBackInRendererOrder() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("strategy", "仓位维持两成");
        notes.put("index", "量能萎缩");

        assertEquals(Arrays.asList("index", "strategy"),
                new ArrayList<>(SectionNotes.fromJson(SectionNotes.toJson(notes)).keySet()));
        assertTrue(ReviewDocFormatter.NOTE_KEYS.indexOf("index") < ReviewDocFormatter.NOTE_KEYS.indexOf("strategy"));
    }
}
