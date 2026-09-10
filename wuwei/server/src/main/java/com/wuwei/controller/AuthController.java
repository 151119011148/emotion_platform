package com.wuwei.controller;

import com.wuwei.dto.LoginRequest;
import com.wuwei.dto.RegisterRequest;
import com.wuwei.service.AuthService;
import com.wuwei.vo.ApiResponse;
import com.wuwei.vo.LoginResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.register(req));
    }
}
