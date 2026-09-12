package com.emotion.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.market.MarketDataException;
import com.emotion.service.ScoreContextService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.HighEcoVO;

/**
 * D5 高位生态（阵眼·抱团·监管）：{@code GET /api/d5/high?date=yyyy-MM-dd}。
 *
 * <p>响应与《D5 融合版 PRD》第十节同构：H/总分/定性 + 阵眼个体 + 抱团与资金 + 监管压制 +
 * 监管反馈 + 监管池 + 交叉信号。装配复用 {@link ScoreContextService#forDate} 同一次取数
 * （监管名单只拉一次、主线 Snapshot 只算一次），不另起取数链路。
 * 阵眼是账号各自的人工判断（t_anchor 绑 user_id），监管名单/抱团是公开事实。
 */
@RestController
@RequestMapping("/api/d5")
public class HighEcoController {

    private final ScoreContextService scoreContext;

    public HighEcoController(ScoreContextService scoreContext) {
        this.scoreContext = scoreContext;
    }

    @GetMapping("/high")
    public ApiResponse<HighEcoVO> high(Authentication auth, @RequestParam(required = false) String date) {
        return ApiResponse.ok(scoreContext.highEco(userId(auth), parse(date)));
    }

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
            throw new MarketDataException("日期格式应为 yyyy-MM-dd，收到：" + raw);
        }
    }
}
