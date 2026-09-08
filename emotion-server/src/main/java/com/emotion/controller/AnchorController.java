package com.emotion.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.entity.Anchor;
import com.emotion.market.MarketDataException;
import com.emotion.service.AnchorMetricsService;
import com.emotion.service.AnchorService;
import com.emotion.vo.AnchorVO;
import com.emotion.vo.ApiResponse;

/**
 * 周期阵眼：登记谁在这轮周期里当阵眼，以及它的跨度和当日反馈。
 *
 * <p>写侧只碰库（{@link AnchorService}），读侧才打行情（{@link AnchorMetricsService}）——
 * 一次腾讯抽风不该让用户连自己的判断都改不了。
 * <p>{@code user_id} 一律取自登录态：阵眼是账号各自的判断，测试号可以设自己的阵眼。
 */
@RestController
@RequestMapping("/api/anchors")
public class AnchorController {

    private static final int DEFAULT_SPAN_DAYS = 120;
    private static final int MAX_SPAN_DAYS = 500;

    private final AnchorService anchorService;
    private final AnchorMetricsService metricsService;

    public AnchorController(AnchorService anchorService, AnchorMetricsService metricsService) {
        this.anchorService = anchorService;
        this.metricsService = metricsService;
    }

    /** 某日在位的阵眼 + 跨度指标 + 第 8 维分数。date 不给就按今天。 */
    @GetMapping
    public ApiResponse<AnchorVO> list(Authentication auth, @RequestParam(required = false) String date) {
        return ApiResponse.ok(metricsService.vo(userId(auth), parse(date, LocalDate.now())));
    }

    /** 曲线画跨度区间用：最近 N 天里与窗口有交集的所有跨度。 */
    @GetMapping("/span")
    public ApiResponse<List<AnchorVO.Span>> spans(Authentication auth,
                                                  @RequestParam(defaultValue = "" + DEFAULT_SPAN_DAYS) int days) {
        LocalDate today = LocalDate.now();
        int window = days < 1 ? DEFAULT_SPAN_DAYS : Math.min(days, MAX_SPAN_DAYS);
        return ApiResponse.ok(metricsService.spans(userId(auth), today.minusDays(window), today));
    }

    /**
     * 曲线第二根轴用：窗口内每天的阵眼涨跌与第 8 维分（当天多只取最差那只）。
     * 与 /span 分开，是因为这两个数只有拉日 K 才知道，而跨度区间不用。
     */
    @GetMapping("/series")
    public ApiResponse<List<AnchorVO.Daily>> series(Authentication auth,
                                                    @RequestParam(defaultValue = "" + DEFAULT_SPAN_DAYS) int days) {
        LocalDate today = LocalDate.now();
        int window = days < 1 ? DEFAULT_SPAN_DAYS : Math.min(days, MAX_SPAN_DAYS);
        return ApiResponse.ok(metricsService.dailySeries(userId(auth), today.minusDays(window), today));
    }

    @PostMapping
    public ApiResponse<Anchor> create(Authentication auth, @RequestBody Anchor anchor) {
        return ApiResponse.ok(anchorService.create(userId(auth), anchor));
    }

    /** 整条替换：弹框里改完整条再提交，endDate 传 null 就是"仍在位"。 */
    @PutMapping("/{id}")
    public ApiResponse<Anchor> update(Authentication auth, @PathVariable Long id, @RequestBody Anchor anchor) {
        return ApiResponse.ok(anchorService.update(userId(auth), id, anchor));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable Long id) {
        anchorService.delete(userId(auth), id);
        return ApiResponse.ok(null);
    }

    private static Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    private static LocalDate parse(String raw, LocalDate fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new MarketDataException("日期格式应为 yyyy-MM-dd，收到：" + raw);
        }
    }
}
