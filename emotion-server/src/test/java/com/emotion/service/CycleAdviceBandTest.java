package com.emotion.service;

import com.emotion.vo.StageAdviceVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 操作建议按「页面上那条带」取。
 *
 * <p>这套词表和落库的 7 档不是一回事（前端读时重切相对带），点历史日时前端发来的是带名，
 * 所以这里钉三件事：带名对到正确的文案、标题印回带名本身、认不出的带名不给建议。
 */
class CycleAdviceBandTest {

    private final CycleService cycle = new CycleService();

    @Test
    void 沸点用的是高潮那份兑现文案标题印沸点() {
        StageAdviceVO advice = cycle.adviceForBand("沸点");
        assertEquals("沸点", advice.getStage());
        assertEquals("逐步兑现", advice.getTone());
        assertEquals("逐步降到5成以下", advice.getPosition());
        assertEquals("分批止盈，落袋为安，坚决不追高不加仓", advice.getAction());
    }

    @Test
    void 强制退潮带名照原样印出来文案吃退潮() {
        StageAdviceVO advice = cycle.adviceForBand("退潮(强制)");
        assertEquals("退潮(强制)", advice.getStage());
        assertEquals("降仓清仓", advice.getTone());
        assertEquals("0-2成", advice.getPosition());
    }

    @Test
    void 冰点与发酵各归各的不会串成今天那一份() {
        assertEquals("空仓观望", cycle.adviceForBand("冰点").getTone());
        assertEquals("0-1成", cycle.adviceForBand("冰点").getPosition());
        assertEquals("持股为主", cycle.adviceForBand("发酵").getTone());
        assertEquals("7成-满仓", cycle.adviceForBand("发酵").getPosition());
    }

    /** 混沌 → 修复是权宜对位（P25–P75 在库里横跨修复/启动/分歧），换文案要他定，这里只钉住现状。 */
    @Test
    void 混沌暂时吃修复那份小仓试错() {
        StageAdviceVO advice = cycle.adviceForBand("混沌");
        assertEquals("混沌", advice.getStage());
        assertEquals("小仓试错", advice.getTone());
        assertEquals("1-3成", advice.getPosition());
    }

    /** 缺维的日子前端不重切，直接回落库标签，那些就是 7 档本名。 */
    @Test
    void 落库本名也认() {
        assertEquals("去弱留强", cycle.adviceForBand("分歧").getTone());
        assertEquals("加仓进攻", cycle.adviceForBand("启动").getTone());
    }

    /** 关键的一条：查不到不能退化成「修复」。getAdvice 老口径会，这条不许跟着学。 */
    @Test
    void 认不出的带名返回空不兜底成修复() {
        assertNull(cycle.adviceForBand("温泉"));
        assertNull(cycle.adviceForBand("   "));
        assertNull(cycle.adviceForBand(null));
        assertEquals("小仓试错", cycle.getAdvice("温泉").getTone());
    }
}
