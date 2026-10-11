package com.emotion.config;

import com.emotion.service.SingleSessionService;
import com.emotion.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.dao.DataAccessException;
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
import java.util.Date;

/**
 * 每个请求的登录判据，三道关按顺序过：
 * <ol>
 *   <li><b>令牌本身</b>：验签、没过期、结构能用。这关失败＝「没登录」，一律往下走空认证。</li>
 *   <li><b>版本号</b>（单点登录互踢）：token 里的 {@code tv} 必须等于库里当前值，
 *       见 {@link SingleSessionService}。对不上＝这个号已经在别处登录过，或被强制下线/删号。</li>
 *   <li><b>空闲</b>：这个会话最近有没有真人发起的请求，见 {@link SessionActivityRegistry}。</li>
 * </ol>
 *
 * <p>任何一道关失败都<b>不抛异常、也不自己写状态码</b>：只是不建立认证上下文，
 * 交给 {@code SecurityConfig} 的 {@code anyRequest().authenticated()} 与那里配的 401 入口出结论。
 * 抛异常会变成 500，而这些情况的真实语义就是「没登录」。
 * 唯一的例外是第二个 try 里的 503，理由写在那段注释上。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final SingleSessionService singleSession;
    private final SessionActivityRegistry activity;

    public JwtAuthFilter(JwtUtil jwtUtil, SingleSessionService singleSession,
                         SessionActivityRegistry activity) {
        this.jwtUtil = jwtUtil;
        this.singleSession = singleSession;
        this.activity = activity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        Long userId;
        String username;
        Integer version;
        String role;
        Date issuedAt;
        try {
            Claims claims = jwtUtil.parseToken(header.substring(7));
            Date expiresAt = claims.getExpiration();
            if (expiresAt == null || expiresAt.before(new Date())) {
                filterChain.doFilter(request, response);
                return;
            }
            userId = claims.get("userId", Long.class);
            username = claims.getSubject();
            version = JwtUtil.versionOf(claims);
            role = JwtUtil.roleOf(claims);
            issuedAt = claims.getIssuedAt();
        } catch (Exception ignored) {
            // 签名不对、被篡改、结构坏掉：语义是「没登录」，这里必须吞掉。
            // 让它冒出去会变成 500，而前端只会说成「网络错误」，人不知道自己没登录。
            filterChain.doFilter(request, response);
            return;
        }

        int currentVersion;
        try {
            currentVersion = singleSession.currentVersion(userId);
        } catch (DataAccessException e) {
            // 版本号这一关要回库，于是库抖会被上面那个「一切 Exception 都当没登录」的写法接住的话，
            // 结果就是一次 MySQL 抖动把在线 token 全清掉、所有人弹回登录页——库抖不该等于全员踢下线。
            // 所以这行查询刻意放在第一个 try 外面，并且当场给 503：
            // 不靠容器默认错误页，是因为那要再走一遍 /error 与安全链，状态码不是我们能保证的。
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        boolean alive = userId != null
                && version != null
                && version.intValue() == currentVersion
                && !activity.expired(userId, issuedAt);
        if (alive) {
            // 记一次活动。心跳 URI 的排除在 SessionActivityRegistry.touch 里面——
            // 心跳也算活动的话，空闲窗口会被浏览器自己续满，这条超时永远触发不了。
            activity.touch(userId, issuedAt, request.getRequestURI());
            Collection<GrantedAuthority> authorities =
                    Collections.<GrantedAuthority>singletonList(
                            new SimpleGrantedAuthority("ROLE_" + role));
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(userId, username, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }
}
