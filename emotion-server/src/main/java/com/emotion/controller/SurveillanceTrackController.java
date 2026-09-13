package com.emotion.controller;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.market.SurveillanceKind;
import com.emotion.service.SurveillanceTrackService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.SurveillanceTrackVO;

/**
 * 监管全生命周期轨迹。读侧补数：某事件窗口未落 t_surveillance_daily 时现推算并落库，
 * 已落的直接读表。监督制股数据公开，不绑用户。
 */
@RestController
@RequestMapping("/api/surveillance")
public class SurveillanceTrackController {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final SurveillanceTrackService trackService;

    public SurveillanceTrackController(SurveillanceTrackService trackService) {
        this.trackService = trackService;
    }

    /** 监管全生命周期热力表数据。默认只看仍在监管期（未出池）的事件；active=false 含历史已出池。 */
    @GetMapping("/track")
    public ApiResponse<SurveillanceTrackVO> track(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String kinds,
            @RequestParam(required = false, defaultValue = "true") boolean active) {
        LocalDate d = parseDate(date, LocalDate.now(CN));
        List<SurveillanceKind> only = parseKinds(kinds);
        return ApiResponse.ok(trackService.track(d, only, active));
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static List<SurveillanceKind> parseKinds(String raw) {
        List<SurveillanceKind> kinds = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return kinds;
        }
        for (String part : raw.split(",")) {
            try {
                kinds.add(SurveillanceKind.valueOf(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // 非法类型忽略，不整串报错
            }
        }
        return kinds;
    }
}