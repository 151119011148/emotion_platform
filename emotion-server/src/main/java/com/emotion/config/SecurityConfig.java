package com.emotion.config;

import com.emotion.config.JwtAuthFilter;
import com.emotion.vo.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    // 跨域白名单外置：本机 dev 默认 localhost/127.0.0.1 的 5173 与 3000；
    // 生产由 application-prod.yml 的 emotion.cors.origins（或环境变量 EMOTION_CORS_ORIGINS）给，
    // 逗号分隔。nginx 同域部署时浏览器根本不发跨域请求，这条只是给直连 8080 / 前端单独起端口兜底。
    @Value("${emotion.cors.origins:http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173,http://127.0.0.1:3000}")
    private String corsOrigins;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors().and()
            .csrf().disable()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeRequests()
                // 只有「换 token 那一步」必须放行：登录时手里还没有 token。
                // 注册虽已收拢（仅超管可调），但它同样拿不到 token，也只能放行，
                // 权限在 AuthController 里判——那里能给出「仅超级管理员可操作账号」这句人话，
                // 而 Spring 的 hasRole 只会回一个空响应体的 403。
                .antMatchers("/api/auth/login", "/api/auth/register").permitAll()
                // /me 与 /logout 必须已登录：被互踢下来的旧 token 要在这里拿到 401/403，
                // 前端拦截器才会清 localStorage 并跳回登录页。放行的话只会拿到业务码 400，
                // 页面停在原处，用户看不出自己已经被顶下线了。
                .antMatchers("/api/auth/**").authenticated()
                .anyRequest().authenticated()
            .and()
            // 未登录的统一出口。不配这一条时 Spring Security 默认用 Http403ForbiddenEntryPoint，
            // 实测生产上「无 token」和「坏 token」都是 403 空响应体——与 V43 注释里
            // 「旧 token 要在这里拿到 401」不符，前端只能把 401/403 当同一件事处理。
            // 名实相符之后，前端才可以只把 401 当掉线，把 403 留给越权与 CORS。
            .exceptionHandling().authenticationEntryPoint(new Json401EntryPoint())
            .and()
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 401 + 一个说得出原因的信封。形状与 {@link ApiResponse} 保持一致——序列化用的就是那个类，
     * 免得哪天字段改了这里还手写着一份过期的。
     */
    static class Json401EntryPoint implements AuthenticationEntryPoint {

        private static final String BODY;

        static {
            try {
                BODY = new ObjectMapper()
                        .writeValueAsString(ApiResponse.error(401, "登录已失效，请重新登录"));
            } catch (Exception e) {
                throw new IllegalStateException("401 信封序列化不出来", e);
            }
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException authException) throws IOException {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(BODY);
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // localhost 与 127.0.0.1 在 CORS 里是两个不同的 origin，必须都列上：dev 走 vite 代理时
        // changeOrigin 只改写 Host、不改写 Origin，后端仍收到浏览器地址栏的 origin，
        // 漏一个就会被 DefaultCorsProcessor 判为非法来源，直接返回 403 Invalid CORS request。
        config.setAllowedOrigins(Arrays.asList(corsOrigins.split("\\s*,\\s*")));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
