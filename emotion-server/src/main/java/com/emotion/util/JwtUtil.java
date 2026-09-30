package com.emotion.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.security.Key;
import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private Key key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    /**
     * 签发令牌。tv（单点登录版本号）与 role 都写进 token：鉴权时不必回库
     * 就知道「这是这个账号的第几次登录」以及「他是什么角色」。
     */
    public String generateToken(Long userId, String username, int tokenVersion, String role) {
        return Jwts.builder()
                .setSubject(username)
                .claim("userId", userId)
                .claim("tv", tokenVersion)
                .claim("role", role == null || role.trim().isEmpty() ? AuthContext.ROLE_USER : role)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public Long getUserId(String token) {
        return parseToken(token).get("userId", Long.class);
    }

    public String getUsername(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 令牌版本号。V43 之前签发的 token 没有 tv 声明 → 返回 null，
     * 调用方按「与当前版本不符」处理，等于强制重新登录一次（顺带把 role 领走）。
     */
    public Integer getTokenVersion(String token) {
        Object raw = parseToken(token).get("tv");
        return raw instanceof Number ? ((Number) raw).intValue() : null;
    }

    public String getRole(String token) {
        Object raw = parseToken(token).get("role");
        return raw == null ? AuthContext.ROLE_USER : String.valueOf(raw);
    }

    public boolean isTokenExpired(String token) {
        try {
            return parseToken(token).getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }
}
