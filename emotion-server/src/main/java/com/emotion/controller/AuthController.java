package com.emotion.controller;

import com.emotion.dto.LoginRequest;
import com.emotion.dto.RegisterRequest;
import com.emotion.service.AuthService;
import com.emotion.util.AuthContext;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.LoginResponse;
import com.emotion.vo.UserVO;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册入口收拢：接口还在（脚本/curl 仍可用），但只对超级管理员开放，前端已无任何入口。
     * 留它不是留后门——开户的正路已经移到 /api/admin/users，这里保留一份等价能力，
     * 省得迁移期还在打老地址的脚本全线 404。
     */
    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest req) {
        AuthContext.requireSuperAdmin("操作账号");
        return ApiResponse.ok(authService.register(req));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    /** 当前登录者是谁：前端进外壳时拉一次，角色被改过 / 被强制下线都能立刻反映。 */
    @GetMapping("/me")
    public ApiResponse<UserVO> me(Authentication auth) {
        return ApiResponse.ok(authService.me(userId(auth)));
    }

    /** 退出：令牌版本号 +1，这个 token 当场作废（不只是前端清 localStorage）。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication auth) {
        authService.logout(userId(auth));
        return ApiResponse.ok(null);
    }

    private static Long userId(Authentication auth) {
        return auth == null ? null : (Long) auth.getPrincipal();
    }
}
