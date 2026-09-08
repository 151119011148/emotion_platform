package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.entity.Surveillance;
import com.emotion.market.SurveillanceKind;
import com.emotion.market.SurvivalMember;

/**
 * 监管期窗口推导。全程不联网、不碰库：喂的是实测出来的公告日期与真实交易日历。
 *
 * 口径（使用者的例子钉死的）：第 k 日 = 公告日 D0 之后第 k 个真实交易日，D0 当天不算。
 * 哈药 08-21 严重异常波动 ⇒ 09-03 是第 9 日、09-04 第 10 日、09-07 第 11 日已出窗。
 * 这条边界就是"周一的退潮二阶段只能靠子段状态机和阵眼支撑"的依据，所以单独断言。
 */
class SurveillanceServiceTest {

    /** 2026-08-17 起连续交易（08-17 是周一，09-04 周五，09-07 周一）。 */
    private static final List<LocalDate> DAYS = days("2026-08-17", "2026-08-18", "2026-08-19",
            "2026-08-20", "2026-08-21", "2026-08-24", "2026-08-25", "2026-08-26", "2026-08-27",
            "2026-08-28", "2026-08-31", "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04",
            "2026-09-07", "2026-09-08", "2026-09-09");

    @Test
    void countsTradingDaysStrictlyAfterTheAnnouncementDate() {
        LocalDate severe = date("2026-08-21");

        assertEquals(0, SurveillanceService.dayIndexAfter(severe, severe, DAYS));
        assertEquals(1, SurveillanceService.dayIndexAfter(severe, date("2026-08-24"), DAYS));
        assertEquals(9, SurveillanceService.dayIndexAfter(severe, date("2026-09-03"), DAYS));
        assertEquals(10, SurveillanceService.dayIndexAfter(severe, date("2026-09-04"), DAYS));
        assertEquals(11, SurveillanceService.dayIndexAfter(severe, date("2026-09-07"), DAYS));
    }

    /** 公告常常落在收盘后或周末：D0 不在序列里也要能数，第一个真实交易日就是第 1 日。 */
    @Test
    void weekendAnnouncementStartsOnTheNextTradingDay() {
        assertEquals(1, SurveillanceService.dayIndexAfter(date("2026-08-22"), date("2026-08-24"), DAYS));
        assertEquals(3, SurveillanceService.dayIndexAfter(date("2026-08-22"), date("2026-08-26"), DAYS));
    }

    /** 停牌不消耗窗口：这只票自己没交易的日子里，序列里没有那根柱子，k 就不会前进。 */
    @Test
    void suspendedDaysDoNotAdvanceTheWindow() {
        List<LocalDate> withHalt = new ArrayList<>(DAYS);
        withHalt.remove(date("2026-08-31"));

        assertEquals(10, SurveillanceService.dayIndexAfter(date("2026-08-21"), date("2026-09-04"), DAYS));
        assertEquals(9, SurveillanceService.dayIndexAfter(date("2026-08-21"), date("2026-09-04"), withHalt));
    }

    @Test
    void severeWindowHoldsForTenDaysThenLetsGo() {
        List<Surveillance> events = Arrays.asList(
                event("600664", "哈药股份", "2026-08-21", SurveillanceKind.SEVERE));

        SurvivalMember onNinth = SurveillanceService.memberOf(events, date("2026-09-03"), DAYS);
        assertNotNull(onNinth);
        assertEquals(9, onNinth.getDayIndex());
        assertEquals(10, onNinth.getDays());
        assertEquals("哈药股份", onNinth.getName());
        assertTrue(onNinth.describe().contains("严重异常波动 08/21 第9/10日"));

        assertNotNull(SurveillanceService.memberOf(events, date("2026-09-04"), DAYS));
        // 第 11 日：下周一它已经不是这一维的样本了
        assertNull(SurveillanceService.memberOf(events, date("2026-09-07"), DAYS));
        // 公告当天也不算在列
        assertNull(SurveillanceService.memberOf(events, date("2026-08-21"), DAYS));
    }

    /** 普通异常波动只给 3 个交易日——三档窗口里唯一会在一周内自己走完的。 */
    @Test
    void abnormalMoveWindowIsThreeTradingDays() {
        List<Surveillance> events = Arrays.asList(
                event("605577", "龙版传媒", "2026-09-02", SurveillanceKind.ZD));

        assertEquals(1, SurveillanceService.memberOf(events, date("2026-09-03"), DAYS).getDayIndex());
        assertEquals(3, SurveillanceService.memberOf(events, date("2026-09-07"), DAYS).getDayIndex());
        assertNull(SurveillanceService.memberOf(events, date("2026-09-08"), DAYS));
    }

    /**
     * 连板途中的升级链：09-02 异常波动 + 09-03 监管工作函 + 09-04 又异常波动。
     * 一只票只能算一个样本，但"为什么今天还在列"得由还剩最久的那起事件来说——
     * 09-04 那起当天 k=0 还没生效，09-02 那起明天就走完，撑着它在列的是 09-03 那起。
     */
    @Test
    void mergesOverlappingWindowsIntoOneSample() {
        List<Surveillance> events = Arrays.asList(
                event("605577", "龙版传媒", "2026-09-02", SurveillanceKind.ZD),
                event("605577", "龙版传媒", "2026-09-03", SurveillanceKind.EXCH),
                event("605577", "龙版传媒", "2026-09-04", SurveillanceKind.ZD));

        SurvivalMember member = SurveillanceService.memberOf(events, date("2026-09-04"), DAYS);

        assertNotNull(member);
        assertEquals(2, member.getEvents().size());
        assertEquals(SurveillanceKind.EXCH, member.getEvents().get(0).getKind());
        assertEquals(1, member.getDayIndex());
        assertEquals(10, member.getDays());
        assertTrue(member.describe().contains(" + "));
    }

    /**
     * 进分那一起必须排在最前，哪怕它剩余窗口更短。哈药 09-04：08-21 那起严重异常已经第 10 日
     * （还剩 0 天），09-03 那起例行异动还剩 2 天——按"剩得久"排会把 ZD 摆到第一行，
     * 于是卡片上出现"kind=异常波动 却进分"这种自相矛盾的一行，dayIndex/days 也说错了窗口。
     */
    @Test
    void scoredEventLeadsEvenWhenItEndsFirst() {
        List<Surveillance> events = Arrays.asList(
                event("600664", "哈药股份", "2026-08-21", SurveillanceKind.SEVERE),
                event("600664", "哈药股份", "2026-09-03", SurveillanceKind.ZD));

        SurvivalMember member = SurveillanceService.memberOf(events, date("2026-09-04"), DAYS);

        assertEquals(SurveillanceKind.SEVERE, member.getEvents().get(0).getKind());
        assertEquals(10, member.getDayIndex());
        assertEquals(10, member.getDays());
        assertTrue(member.scored());
    }

    /** 只发了例行异常波动的票照样在名单上，但它不进第 9 维——人群口径全靠这一个判断撑着。 */
    @Test
    void routineAbnormalMoveIsListedButNotScored() {
        SurvivalMember routine = SurveillanceService.memberOf(
                Arrays.asList(event("000892", "欢瑞世纪", "2026-09-02", SurveillanceKind.ZD)),
                date("2026-09-03"), DAYS);
        assertFalse(routine.scored());
        assertFalse(SurveillanceKind.ZD.scores());
        assertTrue(SurveillanceKind.SEVERE.scores());
        assertTrue(SurveillanceKind.EXCH.scores());
    }

    /** 同一天两份公告（公司自己的异常波动 + 控股股东那份回复）是两个 art_code、一个窗口。 */
    @Test
    void twoNoticesTheSameDayAreOneWindow() {
        List<Surveillance> events = Arrays.asList(
                event("600479", "千金药业", "2026-08-28", SurveillanceKind.ZD),
                event("600479", "千金药业", "2026-08-28", SurveillanceKind.ZD));

        SurvivalMember member = SurveillanceService.memberOf(events, date("2026-08-31"), DAYS);

        assertNotNull(member);
        assertEquals(1, member.getEvents().size());
        assertEquals(1, member.getDayIndex());
        assertEquals(3, member.getDays());
        assertFalse(member.describe().contains(" + "));
    }

    /** 库里出现未知类别（手工塞的、或旧版本写的）不能算进任何窗口，否则一个错串能把整维点亮。 */
    @Test
    void unknownKindInTableIsNotCounted() {
        List<Surveillance> events = Arrays.asList(event("600664", "哈药股份", "2026-08-21", null));

        assertNull(SurveillanceService.memberOf(events, date("2026-09-03"), DAYS));
    }

    private static Surveillance event(String code, String name, String annDate, SurveillanceKind kind) {
        Surveillance event = new Surveillance();
        event.setStockCode(code);
        event.setStockName(name);
        event.setAnnDate(date(annDate));
        event.setKind(kind == null ? "SOMETHING_ELSE" : kind.name());
        event.setTitle(name + " 测试事件");
        event.setArtCode(code + annDate.replace("-", ""));
        return event;
    }

    private static LocalDate date(String raw) {
        return LocalDate.parse(raw);
    }

    private static List<LocalDate> days(String... raw) {
        List<LocalDate> out = new ArrayList<>();
        for (String value : raw) {
            out.add(date(value));
        }
        return out;
    }
}
