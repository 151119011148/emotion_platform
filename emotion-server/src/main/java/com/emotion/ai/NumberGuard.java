package com.emotion.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型产出的<b>数字门卫</b>：AI 只许组织和措辞，任何一个数都必须由系统算好回填。
 *
 * <p>为什么靠事后拦截而不是靠提示词：提示里写"不要输出数字"的模型照样会在措辞顺的地方
 * 冒出一个看起来完全合理的数，而那种数在复盘文档里和真读数长得一模一样，没人看得出来路。
 * 拦在落笔之前，比指望它自觉可靠。
 *
 * <p>分两档是有原因的：阿拉伯数字（含全角）没有歧义，出现即违规；中文数字会误伤
 * 「万一 / 三方 / 一心 / 部分」这类正常词，硬拦会把能用的草稿全打死，所以只在
 * <b>中文数字紧跟计量单位</b>（%、倍、板、亿、日、天、家、分、档、元）时才报，且只报不拦。
 */
public final class NumberGuard {

    /**
     * 任何形式的阿拉伯数字，按「一个数」而不是「一个数位」命中——{@code 52.8} 报一条，
     * 报三条同样的片段等于刷屏。token 名里不含数字，所以命中一定是模型自己写的。
     */
    private static final Pattern DIGIT = Pattern.compile("[0-9０-９]+(?:\\.[0-9０-９]+)?");
    /** 带上下文的片段，给他看清"是哪句话把数写出来了"。 */
    private static final int WINDOW = 12;
    private static final Pattern CN_COUNTED = Pattern.compile(
            "[一二三四五六七八九十百千万亿两半成](?=[%％]?(倍|板|亿|万|日|天|家|分|档|元|个|%)|[%％])");

    private NumberGuard() {
    }

    /** 硬违规清单：空表 = 这份产出可以用。每条给片段而不是只给个数，否则他要自己去找。 */
    public static List<String> violations(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher matcher = DIGIT.matcher(text);
        while (matcher.find()) {
            out.add(snippet(text, matcher.start()));
        }
        return out;
    }

    /** 软警告：中文计量词。不拦，但原样交给他看——这类词多半也是模型在自造读数。 */
    public static List<String> warnings(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher matcher = CN_COUNTED.matcher(text);
        while (matcher.find()) {
            out.add(snippet(text, matcher.start()));
        }
        return out;
    }

    /** 把命中位置附近的原文取出来，压掉换行，好塞进一行提示里。 */
    private static String snippet(String text, int at) {
        int from = Math.max(0, at - WINDOW);
        int to = Math.min(text.length(), at + WINDOW + 1);
        return "…" + text.subSequence(from, to).toString().replaceAll("\\s+", " ") + "…";
    }
}
