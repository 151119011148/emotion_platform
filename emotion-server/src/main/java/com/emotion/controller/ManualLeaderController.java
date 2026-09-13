package com.emotion.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.entity.ManualLeader;
import com.emotion.market.MarketDataException;
import com.emotion.service.ManualLeaderService;
import com.emotion.vo.ApiResponse;

/**
 * 连板天梯人工总龙头：某日用户手动指定谁当"总龙头"。
 *
 * <p>写侧只碰库（{@link ManualLeaderService}），读侧由天梯服务装配标签；
 * {@code user_id} 一律取自登录态——总龙头是账号各自的判断。
 */
@RestController
@RequestMapping("/api/leader")
public class ManualLeaderController {

    private final ManualLeaderService service;

    public ManualLeaderController(ManualLeaderService service) {
        this.service = service;
    }

    /** 某日人工总龙头；没登记返回 <code>data=null</code>。date 缺省按今天。 */
    @GetMapping
    public ApiResponse<ManualLeader> get(Authentication auth, @RequestParam(required = false) String date) {
        LocalDate d = parse(date);
        String code = service.codeOf(userId(auth), d);
        if (code == null) {
            return ApiResponse.ok(null);
        }
        return ApiResponse.ok(decorate(code));
    }

    @PutMapping
    public ApiResponse<ManualLeader> save(Authentication auth, @RequestBody ManualLeader body) {
        return ApiResponse.ok(service.save(userId(auth), parse(body.getTradeDate() == null
                ? null : body.getTradeDate().toString()), body.getCode()));
    }

    @DeleteMapping
    public ApiResponse<Void> clear(Authentication auth, @RequestParam(required = false) String date) {
        service.clear(userId(auth), parse(date));
        return ApiResponse.ok(null);
    }

    private static ManualLeader decorate(String code) {
        ManualLeader m = new ManualLeader();
        m.setCode(code);
        return m;
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