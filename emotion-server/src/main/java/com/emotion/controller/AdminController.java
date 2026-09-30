package com.emotion.controller;

import java.util.List;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.dto.CreateUserRequest;
import com.emotion.dto.UpdateUserRequest;
import com.emotion.service.AdminService;
import com.emotion.util.AuthContext;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.UserVO;

/**
 * 账号管理：注册入口收拢之后唯一的开户/管号通道。
 *
 * <p>权限在 {@link AdminService} 里判一次（真正的判据），这里再判一次是为了给前端
 * 一个说得清的 403——走 Spring 的 {@code hasRole} 会被拦成空响应体的 403，
 * 前端只能统一显示「登录已过期」，把「你不是管理员」说成「你掉线了」。
 *
 * <p>路由不挂 {@code /api/auth/**}：那个前缀在 SecurityConfig 里是 permitAll，
 * 放进去等于把管理员接口也一起开给匿名访问。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public ApiResponse<List<UserVO>> users() {
        requireSuperAdmin();
        return ApiResponse.ok(adminService.listUsers());
    }

    @PostMapping("/users")
    public ApiResponse<UserVO> create(@Valid @RequestBody CreateUserRequest req) {
        requireSuperAdmin();
        return ApiResponse.ok(adminService.createUser(req));
    }

    @PutMapping("/users/{id}")
    public ApiResponse<UserVO> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
        requireSuperAdmin();
        return ApiResponse.ok(adminService.updateUser(id, req));
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requireSuperAdmin();
        adminService.deleteUser(id);
        return ApiResponse.ok(null);
    }

    /** 强制下线：只作废这个账号已签发的 token，账号与密码都不动。 */
    @PostMapping("/users/{id}/kick")
    public ApiResponse<Void> kick(@PathVariable Long id) {
        requireSuperAdmin();
        adminService.kickUser(id);
        return ApiResponse.ok(null);
    }

    private static void requireSuperAdmin() {
        if (!AuthContext.isSuperAdmin()) {
            throw new com.emotion.exception.BizException("仅超级管理员可操作账号");
        }
    }
}
