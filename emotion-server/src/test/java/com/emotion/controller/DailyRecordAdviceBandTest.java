package com.emotion.controller;

import com.emotion.entity.DailyRecord;
import com.emotion.exception.BizException;
import com.emotion.service.CycleService;
import com.emotion.service.DailyRecordService;
import com.emotion.service.ReviewExportService;
import com.emotion.service.ReviewImportService;
import com.emotion.service.ReviewLedgerService;
import com.emotion.util.TemperatureCalculator;
import com.emotion.vo.StageAdviceVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 操作建议跟着页面上那条带走。
 *
 * <p>他报的是「点击某天，是冰点，但还是显示今天的满仓」。这里钉两条：
 * 带名传进来时不查库（查了就有拿别的地方的阶段作答的机会），
 * 以及带名为空、只给 date 时讲的是**那一天**，不再回落到今天或最近一条。
 */
class DailyRecordAdviceBandTest {

    private final DailyRecordService recordService = mock(DailyRecordService.class);
    private final DailyRecordController controller = new DailyRecordController(
            recordService, new CycleService(), mock(ReviewImportService.class),
            mock(ReviewExportService.class), mock(ReviewLedgerService.class), new ObjectMapper());

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void loginAsSuperAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                1L, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @Test
    void 沸点带名出的是高潮那份兑现而且不碰库() {
        loginAsSuperAdmin();
        StageAdviceVO advice = controller.getAdvice("沸点", null).getData();
        assertEquals("沸点", advice.getStage());
        assertEquals("逐步降到5成以下", advice.getPosition());
        verifyNoInteractions(recordService);
    }

    /** 认不出的带名不退化成「修复」：那份文案是一次建仓指令，串错一次就是一次错单。 */
    @Test
    void 认不出的带名给不给建议() {
        loginAsSuperAdmin();
        StageAdviceVO advice = controller.getAdvice("温泉", null).getData();
        assertEquals("温泉", advice.getStage());
        assertEquals("不给出建议", advice.getTone());
        assertEquals("-", advice.getPosition());
    }

    @Test
    void 非超管这条走业务异常不查库() {
        assertThrows(BizException.class, () -> controller.getAdvice("沸点", null));
        verifyNoInteractions(recordService);
    }

    /** 缺维那天前端算不出带名（band 传空串），此时 date 就是这一问的主语。 */
    @Test
    void 空带名加date只看那一天不搬到最近一条() {
        loginAsSuperAdmin();
        LocalDate day = LocalDate.parse("2026-09-04");
        DailyRecord sparse = new DailyRecord();
        sparse.setTradeDate(day);
        sparse.setStage("");
        int dims = TemperatureCalculator.MIN_DIMS_FOR_STAGE - 2;
        sparse.setScoredDims(dims);
        when(recordService.getByDate(day)).thenReturn(sparse);

        StageAdviceVO advice = controller.getAdvice("", "2026-09-04").getData();
        assertEquals("数据不足", advice.getStage());
        assertTrue(advice.getAction().contains(dims + " 维参与打分"), advice.getAction());
        assertTrue(advice.getAction().contains(
                "少于 " + TemperatureCalculator.MIN_DIMS_FOR_STAGE + " 维"), advice.getAction());
        verify(recordService).getByDate(day);
        verify(recordService, never()).getToday();
        verify(recordService, never()).getLatest(anyInt());
    }

    @Test
    void 不带任何参数时仍是老口径今天回落最近一条() {
        loginAsSuperAdmin();
        when(recordService.getToday()).thenReturn(null);
        DailyRecord latest = new DailyRecord();
        latest.setStage("冰点");
        when(recordService.getLatest(1)).thenReturn(Collections.singletonList(latest));

        StageAdviceVO advice = controller.getAdvice(null, null).getData();
        assertEquals("冰点", advice.getStage());
        assertEquals("0-1成", advice.getPosition());
    }
}
