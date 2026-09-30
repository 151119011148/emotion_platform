package com.emotion.config;

import com.emotion.config.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
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
