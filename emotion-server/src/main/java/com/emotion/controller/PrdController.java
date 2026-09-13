package com.emotion.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.dto.MainlinePromoteRequest;
import com.emotion.market.MarketDataException;
import com.emotion.service.MainlineService;
import com.emotion.service.PrdMetricsService;
import com.emotion.service.ShoubanService;
import com.emotion.service.TiantiService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.MainlineVO;
import com.emotion.vo.ShoubanVO;
import com.emotion.vo.TiantiVO;

/**
 * PRD 2.0 三条只读页面接口：连板天梯 / 首板池 / 主线详情。
 *
 * <p>都要登录态：主线判定与龙头标签按账号的题材登记对齐（催化剂硬度取自各自的 t_theme），
 * 主线详情还会读当日人工列。服务间共用 {@link PrdMetricsService} 快照——三个页面看到的
 * 主线与打分引擎进分的是同一份，不存在页面一套、算分一套。
 */
@RestController
@RequestMapping("/api")
public class PrdController {

    private final TiantiService tiantiService;
    private final ShoubanService shoubanService;
    private final MainlineService mainlineService;

    public PrdController(TiantiService tiantiService,
                         ShoubanService shoubanService,
                         MainlineService mainlineService) {
        this.tiantiService = tiantiService;
        this.shoubanService = shoubanService;
        this.mainlineService = mainlineService;
    }

    /** 连板天梯：四层分组 + 龙头分工标签（PRD P2）。 */
    @GetMapping("/tianti")
    public ApiResponse<TiantiVO> tianti(Authentication auth,
                                        @RequestParam(required = false) String date) {
        return ApiResponse.ok(tiantiService.vo(userId(auth), parse(date)));
    }

    /** 首板池：封住/炸板两表 + 1 进 2 晋级统计（PRD P3）。 */
    @GetMapping("/shouban")
    public ApiResponse<ShoubanVO> shouban(Authentication auth,
                                          @RequestParam(required = false) String date) {
        return ApiResponse.ok(shoubanService.vo(userId(auth), parse(date)));
    }

    /** 主线详情：五要素 + 生命周期 + 龙头分工 + 轮动信号（PRD P6）。 */
    @GetMapping("/mainline")
    public ApiResponse<MainlineVO> mainline(Authentication auth,
                                            @RequestParam(required = false) String date) {
        return ApiResponse.ok(mainlineService.vo(userId(auth), parse(date)));
    }

    /** 双轨 v0.2：雷达区「升级到主线区」→ 落人工主线标记，返回更新后的双轨数据。 */
    @PostMapping("/mainline/promote")
    public ApiResponse<MainlineVO> promote(Authentication auth,
                                           @RequestBody MainlinePromoteRequest req) {
        return ApiResponse.ok(mainlineService.promote(userId(auth), req.getTradeDate(), req.getIndustry()));
    }

    /** 双轨 v0.2：取消人工主线标记，返回更新后的双轨数据。 */
    @DeleteMapping("/mainline/promote")
    public ApiResponse<MainlineVO> cancel(Authentication auth,
                                          @RequestBody MainlinePromoteRequest req) {
        return ApiResponse.ok(mainlineService.cancel(userId(auth), req.getTradeDate(), req.getIndustry()));
    }

    /** 登录态里带的是账号 id（JwtAuthFilter 放进 principal），题材行按它查各自的登记。 */
    private static Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            // 这里必须是中文：GlobalExceptionHandler 会把 message 原样弹到界面上
            throw new MarketDataException("日期格式应为 yyyy-MM-dd，收到：" + raw);
        }
    }
}
