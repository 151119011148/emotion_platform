package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.emotion.vo.SurveillanceTrackVO;

/**
 * 监管生命周期状态机：{@link SurveillanceTrackService#statusOf} 五态分类。
 */
class SurveillanceTrackServiceTest {

    @Test
    void avoid_monitorIgnored_forward() {
        SurveillanceTrackVO.Status s = SurveillanceTrackService.statusOf(false, true, false, 4);
        assertEquals("绕异动", s.getName());
        assertEquals("警示", s.getTone());
    }

    @Test
    void risenThenSunk_wasUpThenNuked() {
        SurveillanceTrackVO.Status s = SurveillanceTrackService.statusOf(false, true, true, 4);
        assertEquals("先扬后抑", s.getName());
        assertEquals("危险", s.getTone());
    }

    @Test
    void effective_nukeNoBoard() {
        SurveillanceTrackVO.Status s = SurveillanceTrackService.statusOf(false, false, true, 0);
        assertEquals("监管生效", s.getName());
        assertEquals("危险", s.getTone());
    }

    @Test
    void effective_flatWeak() {
        SurveillanceTrackVO.Status s = SurveillanceTrackService.statusOf(false, false, false, 0);
        assertEquals("监管生效", s.getName());
        assertEquals("中性", s.getTone());
    }

    @Test
    void exited_afterWindow() {
        SurveillanceTrackVO.Status s = SurveillanceTrackService.statusOf(true, true, true, 4);
        assertEquals("已出池", s.getName());
    }
}