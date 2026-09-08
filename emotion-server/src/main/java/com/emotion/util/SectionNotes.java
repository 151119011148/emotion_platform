package com.emotion.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code t_daily_record.doc_notes} 那一列的 JSON 读写。
 *
 * <p>只做 Map ↔ String，<b>绝不解析正文内容</b>：这些格子存在的意义就是"你把元宝/豆包给你的答案
 * 原样贴进来，我原样带回去"。一旦这里开始从正文里找题材、找概率，就有了第二套口径和第二个会错的判据。
 */
public final class SectionNotes {

    private static final Logger log = LoggerFactory.getLogger(SectionNotes.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE =
            new TypeReference<LinkedHashMap<String, String>>() {
            };

    private SectionNotes() {
    }

    /** 全空 → null，好让 ALWAYS 策略把这一列清回"从没填过"而不是"填了个空表"。 */
    public static String toJson(Map<String, String> notes) {
        Map<String, String> kept = normalize(notes);
        if (kept.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(kept);
        } catch (Exception e) {
            log.warn("判断文字序列化失败，这次不写: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 库里存的东西一律可能坏（手改过库、键名换过），解析不了就当没有，不能让详情页 500。 */
    public static Map<String, String> fromJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return normalize(MAPPER.readValue(raw, MAP_TYPE));
        } catch (Exception e) {
            log.warn("doc_notes 不是合法 JSON，按空处理: {}", e.getClass().getSimpleName());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 按 {@link ReviewDocFormatter#NOTE_KEYS} 的顺序归置，丢掉空正文。
     *
     * <p>刻意<b>不</b>丢掉没登记过的键：这些格子里存的是他写的判断，是全篇最贵的东西。
     * 哪一天小节改了名，按键过滤等于把他的字静默删掉；留着它们，渲染器会把挂不上号的正文
     * 单列一节露出来（见 {@code ReviewDocFormatter.orphanNotes}），看得见才改得回来。
     */
    private static Map<String, String> normalize(Map<String, String> notes) {
        Map<String, String> kept = new LinkedHashMap<>();
        if (notes == null) {
            return kept;
        }
        for (String key : ReviewDocFormatter.NOTE_KEYS) {
            putIfHasText(kept, key, notes.get(key));
        }
        for (Map.Entry<String, String> entry : notes.entrySet()) {
            if (!ReviewDocFormatter.NOTE_KEYS.contains(entry.getKey())) {
                putIfHasText(kept, entry.getKey(), entry.getValue());
            }
        }
        return kept;
    }

    private static void putIfHasText(Map<String, String> sink, String key, String value) {
        if (key != null && value != null && !value.trim().isEmpty()) {
            sink.put(key, value);
        }
    }
}
