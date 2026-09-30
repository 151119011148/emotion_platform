package com.emotion.config;

import com.emotion.service.SingleSessionService;
import com.emotion.util.JwtUtil;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final SingleSessionService singleSession;

    public JwtAuthFilter(JwtUtil jwtUtil, SingleSessionService singleSession) {
        this.jwtUtil = jwtUtil;
        this.singleSession = singleSession;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                if (!jwtUtil.isTokenExpired(token)) {
                    Long userId = jwtUtil.getUserId(token);
                    String username = jwtUtil.getUsername(token);
                    Integer version = jwtUtil.getTokenVersion(token);
                    String role = jwtUtil.getRole(token);
                    // 单点登录：版本号对不上 = 这个账号已经在别处登录（或被管理员强制下线）。
                    // 刻意不建立认证上下文、也不抛异常——抛了会变成 500，而真实语义是「没登录」，
                    // 交给 SecurityConfig 的 anyRequest().authenticated() 出 401 才是对的。
                    boolean alive = userId != null && version != null
                            && version.intValue() == singleSession.currentVersion(userId);
                    if (alive) {
                        Collection<GrantedAuthority> authorities =
                                Collections.<GrantedAuthority>singletonList(
                                        new SimpleGrantedAuthority("ROLE_" + role));
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(userId, username, authorities);
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        filterChain.doFilter(request, response);
    }
}
