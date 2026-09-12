package com.emotion.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.market.MarketDataException;
import com.emotion.service.IntradayCoreService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.IntradayCoreVO;

/**
 * 日内核心页（PRD 页面A）：当日板块强度排名 + 板块下钻。
 * 读的是公开三池明细 t_market_stock，要登录但不区分账号（同 /api/market/stocks）。
 */
@RestController
@RequestMapping("/api/intraday")
public class IntradayCoreController {

    private final IntradayCoreService intradayCoreService;

    public IntradayCoreController(IntradayCoreService intradayCoreService) {
        this.intradayCoreService = intradayCoreService;
    }

    /**
     * 当日板块强度排名。
     *
     * @param date yyyy-MM-dd，不传=今天；当天无明细时 available=false
     * @param sort strength(默认)/lb=涨停家数/board=最高板/seal=封单额
     */
    @GetMapping("/core")
    public ApiResponse<IntradayCoreVO> core(@RequestParam(required = false) String date,
                                            @RequestParam(required = false, defaultValue = "strength") String sort) {
        return ApiResponse.ok(intradayCoreService.core(parse(date), sort));
    }

    /** 板块下钻：该行业当日涨停逐只（连板高→低），喂天梯卡片。 */
    @GetMapping("/core/sectors/{industry}/stocks")
    public ApiResponse<IntradayCoreVO.SectorStocks> sectorStocks(@PathVariable String industry,
                                                                 @RequestParam(required = false) String date) {
        return ApiResponse.ok(intradayCoreService.sectorStocks(parse(date), industry));
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            // 中文 message 会被 GlobalExceptionHandler 原样弹到界面
            throw new MarketDataException("日期格式应为 yyyy-MM-dd，收到：" + raw);
        }
    }
}
