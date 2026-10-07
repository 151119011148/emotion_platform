package com.emotion.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 回填是数值进文档的唯一入口，所以这里盯三件事：<b>只认系统给过值的令牌</b>、
 * <b>认不出来的原样退回而不是猜一个</b>、<b>值本身带花括号或符号也不会把替换搞歪</b>。
 */
class TokenFillerTest {

    private static Map<String, String> facts() {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("温度", "52.8°");
        f.put("成交额", "14380.18 亿");
        f.put("封板率", "81%");
        f.put("特殊", "a dollar $1 与 {嵌套}");
        return f;
    }

    @Test
    void knownTokensAreReplacedEverywhereTheyAppear() {
        assertEquals("温度 52.8°，比 52.8° 那一期已经下来了。",
                TokenFiller.fill("温度 {温度}，比 {温度} 那一期已经下来了。", facts()));
    }

    /** 值里带 {@code $} 和花括号都不能把 Matcher 的替换语法带跑。 */
    @Test
    void valuesWithRegexReplacementMetacharactersLandVerbatim() {
        assertEquals("a dollar $1 与 {嵌套}", TokenFiller.fill("{特殊}", facts()));
    }

    @Test
    void unknownTokensAreReportedAndLeftAlone() {
        String draft = "封板率 {封板率}，主力 {主力动向}。";
        Set<String> unknown = TokenFiller.unknownTokens(draft, facts());
        assertEquals(1, unknown.size(), unknown.toString());
        assertTrue(unknown.contains("主力动向"), unknown.toString());
        assertEquals("封板率 81%，主力 {主力动向}。", TokenFiller.fill(draft, facts()));
    }

    @Test
    void bracesSplitOverLinesAreNotTokens() {
        assertTrue(TokenFiller.unknownTokens("这里有一对 {\n花括号\n}", facts()).isEmpty());
    }

    @Test
    void plainTextWithoutBracesPassesThrough() {
        assertEquals("没有引用任何数。", TokenFiller.fill("没有引用任何数。", facts()));
        assertNull(TokenFiller.fill(null, facts()));
    }
}
