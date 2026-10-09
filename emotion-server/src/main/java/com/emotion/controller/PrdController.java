package com.emotion.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.dto.MainlinePromoteRequest;
import com.emotion.market.MarketDataException;
import com.emotion.service.BreakDetailService;
import com.emotion.service.MainlineService;
import com.emotion.service.PrdMetricsService;
import com.emotion.service.ShoubanService;
import com.emotion.service.TiantiService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.BreakDetailVO;
import com.emotion.vo.MainlineVO;
import com.emotion.vo.ShoubanVO;
import com.emotion.vo.TiantiVO;

/**
 * PRD 2.0 三条只读页面接口：连板天梯 / 首板池 / 主线详情。
 *
 * <p>主线判定与龙头标签读全平台共享的题材登记（催化剂硬度取自 t_theme），主线详情还读当日人工列。
 * 服务间共用 {@link PrdMetricsService} 快照——三个页面看到的
 * 主线与打分引擎进分的是同一份，不存在页面一套、算分一套。
 */
@RestController
@RequestMapping("/api")
public class PrdController {

    private final TiantiService tiantiService;
    private final ShoubanService shoubanService;
    private final MainlineService mainlineService;
    private final BreakDetailService breakDetailService;

    public PrdController(TiantiService tiantiService,
                         ShoubanService shoubanService,
                         MainlineService mainlineService,
                         BreakDetailService breakDetailService) {
        this.tiantiService = tiantiService;
        this.shoubanService = shoubanService;
        this.mainlineService = mainlineService;
        this.breakDetailService = breakDetailService;
    }

    /** 连板天梯：四层分组 + 龙头分工标签（PRD P2）。 */
    @GetMapping("/tianti")
    public ApiResponse<TiantiVO> tianti(@RequestParam(required = false) String date) {
        return ApiResponse.ok(tiantiService.vo(parse(date)));
    }

    /**
     * 连板高度曲线：日期区间内每天一个点，y=当日最高板，附并列最高板个股。
     * 前端画"连板高度走势"用，一条 SQL 出 N 天数据，hover 看个股、破前高标点。
     *
     * <p>{@code ladder=true} 才把当天 2 板以上的完整名单带回来：节点页靠它在前端算龙头票／节点票
     * 的逐日轨迹。默认不带——天梯页一次拉 500 天，用不上还得多传几千个对象。
     */
    @GetMapping("/tianti/height-range")
    public ApiResponse<List<TiantiVO.HeightPoint>> heightRange(@RequestParam String start,
                                                              @RequestParam String end,
                                                              @RequestParam(name = "ladder", defaultValue = "false")
                                                              boolean ladder) {
        return ApiResponse.ok(tiantiService.heightRange(parse(start), parse(end), ladder));
    }

    /** 破壁详情：曲线上 ☆（试探）/★（破壁成功）那天的助攻、盘口、情绪闸门与次日结算。 */
    @GetMapping("/tianti/break-detail")
    public ApiResponse<BreakDetailVO> breakDetail(@RequestParam(required = false) String date) {
        return ApiResponse.ok(breakDetailService.vo(parse(date)));
    }

    /** 首板池：封住/炸板两表 + 1 进 2 晋级统计（PRD P3）。 */
    @GetMapping("/shouban")
    public ApiResponse<ShoubanVO> shouban(@RequestParam(required = false) String date) {
        return ApiResponse.ok(shoubanService.vo(parse(date)));
    }

    /** 主线详情：五要素 + 生命周期 + 龙头分工 + 轮动信号（PRD P6）。 */
    @GetMapping("/mainline")
    public ApiResponse<MainlineVO> mainline(@RequestParam(required = false) String date) {
        return ApiResponse.ok(mainlineService.vo(parse(date)));
    }

    /** 双轨 v0.2：雷达区「升级到主线区」→ 落人工主线标记，返回更新后的双轨数据。 */
    @PostMapping("/mainline/promote")
    public ApiResponse<MainlineVO> promote(@RequestBody MainlinePromoteRequest req) {
        return ApiResponse.ok(mainlineService.promote(req.getTradeDate(), req.getIndustry()));
    }

    /** 双轨 v0.2：取消人工主线标记，返回更新后的双轨数据。 */
    @DeleteMapping("/mainline/promote")
    public ApiResponse<MainlineVO> cancel(@RequestBody MainlinePromoteRequest req) {
        return ApiResponse.ok(mainlineService.cancel(req.getTradeDate(), req.getIndustry()));
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
