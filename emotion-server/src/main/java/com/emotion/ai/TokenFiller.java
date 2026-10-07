package com.emotion.ai;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型交回来的令牌换成系统算好的值——数值进文档的<b>唯一</b>入口。
 *
 * <p>顺序是定死的：先过 {@link NumberGuard}（查的是<b>回填前</b>的原文，那时句子里只有令牌），
 * 再回填。反过来做，门卫就会把系统自己填进去的读数当成模型自造的数拦掉。
 *
 * <p>认不出的花括号一律算违规、原样退回，不猜、不留：{@code {昨日情绪}} 猜成什么都是编的，
 * 而留在文里就是一行永远填不上的花括号。
 */
public final class TokenFiller {

    private static final Pattern TOKEN = Pattern.compile("\\{([^{}\\n]{1,16})\\}");

    private TokenFiller() {
    }

    /** 模型写出但令牌表里没有的名字（不带花括号）。空表 = 这份产出每一处引用都对得上系统给的东西。 */
    public static Set<String> unknownTokens(String draft, Map<String, String> facts) {
        Set<String> out = new LinkedHashSet<String>();
        if (draft == null) {
            return out;
        }
        Matcher matcher = TOKEN.matcher(draft);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (facts == null || !facts.containsKey(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** 已知令牌 → 系统值；未知令牌原样保留（调用方先看 {@link #unknownTokens} 决定要不要用这份文本）。 */
    public static String fill(String draft, Map<String, String> facts) {
        if (draft == null || draft.indexOf('{') < 0) {
            return draft;
        }
        Matcher matcher = TOKEN.matcher(draft);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String value = facts == null ? null : facts.get(name);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 一句违规理由：这些令牌系统没给过值，不猜。 */
    public static String unknownMessage(Set<String> unknown) {
        return "写出了系统没给的令牌 " + String.join("、", unknown);
    }
}
