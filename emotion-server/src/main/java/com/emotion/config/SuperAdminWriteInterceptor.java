package com.emotion.config;

import com.emotion.exception.BizException;
import com.emotion.util.AuthContext;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;

/**
 * 业务数据的写门槛：非超管只能读。
 *
 * <p>业务数据全平台共享之后，「谁都能改同一份」是比按账号隔离更危险的状态，所以改一律要超管。
 * 放在一个拦截器里而不是给 43 个写接口各加一行，是因为逐接口加会漂——新接口忘了加就是静默放开，
 * 而这里默认拦、只把例外列明白，忘加例外只会误伤、不会漏放。
 *
 * <p>刻意不用 {@code SecurityConfig.antMatchers(...).hasRole("SUPER_ADMIN")}：Spring 的 403
 * 是空响应体的，而 {@code emotion-web/src/api/index.js:46} 把 401/403 当同一件事处理——清 token、
 * 跳登录、提示「登录已失效」。他刚见过这个现象（新账号看着像空库）。改抛 {@link BizException}
 * 走 {@code GlobalExceptionHandler} 的 HTTP 200 + {@code code=400} + 中文消息，
 * 前端成功分支原样把消息弹出来，人不会被踢下线。
 *
 * <p>只管 POST/PUT/DELETE/PATCH。<b>GET 副作用不在这里的射程内</b>（{@code /api/intraday/themes}
 * 会回填 {@code t_theme_stock}、{@code /api/nodes/current} 会写 {@code last_recalc_at}），
 * 那些写在服务内各自按 {@link AuthContext#isSuperAdmin()} 跳过。
 */
@Component
public class SuperAdminWriteInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /**
     * 非超管也能写的例外，逐条写清为什么。
     */
    private static final List<String> EXEMPT = Arrays.asList(
            // 登录/登出/注册：register 已在 AuthController 里自判，且它拿不到 token，只能放行到这里再判
            "/api/auth/**",
            // 持仓台账：这是「你的账」，本来就该各账号各记，不是共享业务数据
            "/api/records/positions",
            "/api/records/positions/**",
            // 已有专属文案的两处：别用通用消息把「仅超级管理员可操作账号/打分配置」盖掉
            "/api/admin/**",
            "/api/scoring/**",
            // POST 皮质的读：ReviewAiDraftService 零 mapper 写入，拦它只会让复盘页按钮莫名变灰
            "/api/review/ai-draft"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        String path = request.getRequestURI();
        // 只管 /api 下的业务口；/error 这类容器内部转发不在射程内
        if (!path.startsWith("/api/")) {
            return true;
        }
        for (String pattern : EXEMPT) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        if (!AuthContext.isSuperAdmin()) {
            throw new BizException("业务数据全平台共享，只有超级管理员能修改；持仓台账各账号各自记");
        }
        return true;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }
}
