package com.emotion.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 提示词的三条硬约定，全靠这几条断言兜着：<b>全文没有阿拉伯数字</b>、<b>系统的值一个字都没发出去</b>、
 * <b>只列出当天真取到的令牌</b>。
 *
 * <p>前两条是「模型不许产数」的兑现方式：它看不见数，就没有可抄的数，也就没有可编的数。
 * 哪天有人图省事把 {@code facts} 的值直接拼进 prompt，这两条会立刻红——那正是这套设计被拆掉的方式。
 */
class ReviewAiPromptTest {

    private static Map<String, String> facts() {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("日期", "9/30（周三）");
        f.put("温度", "52.8°");
        f.put("阶段", "混沌");
        f.put("成交额", "14380.18 亿");
        f.put("涨停", "52");
        return f;
    }

    @Test
    void promptCarriesNoArabicDigitAtAll() {
        ReviewAiPrompt.Prompt p = ReviewAiPrompt.build(facts());
        assertFalse(p.system.matches("(?s).*[0-9０-９].*"), p.system);
        assertFalse(p.user.matches("(?s).*[0-9０-９].*"), p.user);
    }

    /** 值不出去，出去的只有名字——「混沌」「周三」这种不带数的值也要挡住，否则泄露是不对称的。 */
    @Test
    void noSystemValueLeaksIntoThePrompt() {
        Map<String, String> f = facts();
        String user = ReviewAiPrompt.build(f).user;
        for (Map.Entry<String, String> e : f.entrySet()) {
            assertFalse(user.contains(e.getValue()), "值漏进 prompt：" + e.getKey() + "=" + e.getValue());
        }
    }

    @Test
    void onlyTokensTheSystemActuallyHasAreOffered() {
        String user = ReviewAiPrompt.build(facts()).user;
        assertTrue(user.contains("{温度}"), user);
        assertTrue(user.contains("{成交额}"), user);
        assertFalse(user.contains("{一字}"), "缺数却还发令牌：" + user);
        assertFalse(user.contains("{持仓}"), user);
    }

    /** 每个发出去的令牌都要有语义说明，否则模型只能按名字猜。 */
    @Test
    void everyOfferedTokenIsExplained() {
        Map<String, String> f = new LinkedHashMap<String, String>();
        for (String key : ReviewAiPrompt.GLOSSARY.keySet()) {
            f.put(key, "占位");
        }
        String user = ReviewAiPrompt.build(f).user;
        for (Map.Entry<String, String> e : ReviewAiPrompt.GLOSSARY.entrySet()) {
            assertTrue(user.contains("{" + e.getKey() + "}"), "令牌没发出去：" + e.getKey());
            assertTrue(user.contains(e.getValue()), "令牌没解释：" + e.getKey());
        }
    }

    /** 令牌表比词表跑得快时（改了 aiFacts 忘了这里）：照样发出去，只是没说明，草稿不该整条失败。 */
    @Test
    void unknownTokenStillGoesOutWithAFallbackLine() {
        Map<String, String> f = facts();
        f.put("回封率", "62.5%");
        String user = ReviewAiPrompt.build(f).user;
        assertTrue(user.contains("{回封率}"), user);
        assertTrue(user.contains("没有说明"), user);
    }

    @Test
    void retryKeepsTheRulesAndAppendsTheCorrection() {
        ReviewAiPrompt.Prompt first = ReviewAiPrompt.build(facts());
        ReviewAiPrompt.Prompt again = ReviewAiPrompt.retry(first, "你的产出违规：…52…");
        assertEquals(first.system, again.system);
        assertTrue(again.user.startsWith(first.user), again.user);
        assertTrue(again.user.contains("你的产出违规"), again.user);
    }
}
