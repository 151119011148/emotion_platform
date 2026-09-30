package com.emotion.util;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 取当前登录态的统一入口。
 *
 * <p>认证主体是 {@code userId}（见 {@code JwtAuthFilter}：{@code UsernamePasswordAuthenticationToken}
 * 的 principal 直接放 Long），所以这里不做用户名反查——要用户名也别去查库，
 * token 的 subject 已经在 credentials 里了。
 *
 * <p>角色以 {@code ROLE_*} 权限的形式挂在认证上，来源是登录时签进 token 的 {@code role} 声明；
 * 改角色会顺带 +1 令牌版本号（见 {@code AdminService}），把旧 token 踢掉，
 * 免得「库里是 USER、token 里还是 SUPER_ADMIN」两个真相并存。
 */
public final class AuthContext {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private AuthContext() {
    }

    /** 当前登录用户的 id；未登录（或令牌已被踢下线）时为 {@code null}。 */
    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof Long ? (Long) principal : null;
    }

    public static Optional<Long> currentUserIdOpt() {
        return Optional.ofNullable(currentUserId());
    }

    public static boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (("ROLE_" + ROLE_SUPER_ADMIN).equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
