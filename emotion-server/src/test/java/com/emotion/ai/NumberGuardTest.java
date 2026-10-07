package com.emotion.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 数字门卫：模型的产出里出现阿拉伯数字就是硬违规，中文计量词只警告。
 *
 * <p>这两档分错方向都会毁掉整件事：只靠提示词拦不住（它编出来的数和真读数长得一样），
 * 而把中文数字也硬拦，「万一」「三方会诊」「一部分」这类正常措辞会把好草稿全打死。
 * 所以断言盯着两头：<b>该拦的一个不漏，不该拦的一个不误伤</b>。
 */
class NumberGuardTest {

    @Test
    void anyArabicDigitIsAViolationAndTheSnippetShowsWhichClause() {
        List<String> v = NumberGuard.violations("温度 52.8°，还在修复的窗口里。");
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("52.8"), v.toString());
    }

    /** 全角也是数：{@code ５２} 在文档里一样会被当成读数读进去。 */
    @Test
    void fullWidthDigitsCountToo() {
        assertFalse(NumberGuard.violations("成交额 １４３８０ 亿").isEmpty());
    }

    @Test
    void tokensAndPlainProseAreClean() {
        assertTrue(NumberGuard.violations("温度 {温度} 比 {昨温度} 低，广度 {红盘率} 也跟上来了。").isEmpty());
        assertTrue(NumberGuard.warnings("涨停缩了但封板率还在抬，属于弱修复而不是转强。").isEmpty());
    }

    /** 中文数字紧跟计量单位 = 它在自己造一个读数，报出来但不拦。 */
    @Test
    void chineseNumeralsNextToAUnitWarn() {
        List<String> w = NumberGuard.warnings("量能放出三倍，跌停却只有两家。");
        assertEquals(2, w.size(), w.toString());
    }

    /** 这是"只报不拦"的全部理由：这些词天天用，硬拦等于让草稿永远出不来。 */
    @Test
    void ordinaryChineseWordsAreNotCounts() {
        assertTrue(NumberGuard.warnings("万一是假突破，仓位跟着中军走，不留侥幸。").isEmpty());
    }

    @Test
    void nullAndEmptyAreNotViolations() {
        assertTrue(NumberGuard.violations(null).isEmpty());
        assertTrue(NumberGuard.violations("").isEmpty());
        assertTrue(NumberGuard.warnings(null).isEmpty());
    }
}
