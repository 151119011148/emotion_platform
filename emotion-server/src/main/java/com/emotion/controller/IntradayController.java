package com.emotion.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.dto.ThemeBindRequest;
import com.emotion.market.MarketDataException;
import com.emotion.service.IntradayService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.IntradayVO;
import com.emotion.vo.ThemeStockVO;

/**
 * 日内核心·题材表（板块表没有的"概念"维度）：
 * 题材跨行业、一票可归多个题材，所以题材计数走 is_primary 主题材去重；题材表独有
 * 催化剂硬度 / 生命周期 / 关联板块三列。读取会自动回填热门行业题材（含历史补数）。
 */
@RestController
@RequestMapping("/api")
public class IntradayController {

    private final IntradayService intradayService;

    public IntradayController(IntradayService intradayService) {
        this.intradayService = intradayService;
    }

    /** 题材表：按题材归并当日主题材涨停股，算强度，附未归类统计。读取触发自动回填。 */
    @GetMapping("/intraday/themes")
    public ApiResponse<IntradayVO> themes(Authentication auth,
                                          @RequestParam(required = false) String date) {
        return ApiResponse.ok(intradayService.themeTable(userId(auth), parse(date)));
    }

    /** 题材阶梯：该题材当日绑定个股（主+辅）按连板分层。 */
    @GetMapping("/intraday/themes/{themeId}/tiers")
    public ApiResponse<List<ThemeStockVO.Tier>> tiers(Authentication auth,
                                                      @PathVariable Long themeId,
                                                      @RequestParam(required = false) String date) {
        return ApiResponse.ok(intradayService.tiers(userId(auth), themeId, parse(date)));
    }

    /** 题材→板块映射：该题材主题材个股横跨的行业+计数。 */
    @GetMapping("/intraday/themes/{themeId}/industries")
    public ApiResponse<List<ThemeStockVO.IndustryCount>> industries(Authentication auth,
                                                                    @PathVariable Long themeId,
                                                                    @RequestParam(required = false) String date) {
        return ApiResponse.ok(intradayService.industries(userId(auth), themeId, parse(date)));
    }

    /** 人工归类：把若干代码设进某题材某日（主/辅题材，幂等）。 */
    @PostMapping("/theme/stock/bind")
    public ApiResponse<Integer> bind(Authentication auth, @RequestBody ThemeBindRequest req) {
        return ApiResponse.ok(intradayService.bind(userId(auth), req.getThemeId(), req.getTradeDate(),
                req.getCodes(), req.isPrimary()));
    }

    /** 未归类：当日全市场涨停股里没被归入任何题材的独立股票。 */
    @GetMapping("/theme/unassigned")
    public ApiResponse<List<ThemeStockVO.StockLine>> unassigned(Authentication auth,
                                                                @RequestParam(required = false) String date) {
        return ApiResponse.ok(intradayService.unassigned(userId(auth), parse(date)));
    }

    private static Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new MarketDataException("日期格式应为 yyyy-MM-dd，收到：" + raw);
        }
    }
}