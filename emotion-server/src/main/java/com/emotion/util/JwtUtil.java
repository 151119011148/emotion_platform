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
     *
     * <p>{@code iat}（签发时刻）不只是留档：空闲超时把它和 userId 一起当会话键
     * （见 {@code SessionActivityRegistry}），新登录必然带新 iat，天然就是一个新会话。
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

    /**
     * 验签并解出声明。签名不对／结构坏掉／已过期都会抛 {@code JwtException}，
     * 由调用方（{@code JwtAuthFilter}）决定怎么收场。
     *
     * <p>这里只留这一个读取入口：以前下面还挂着 getUserId／getUsername／getTokenVersion／
     * getRole／isTokenExpired 五个「传 token 拿单个字段」的方法，鉴权热路径上连着调五行，
     * 等于每个请求把同一枚 token 验五次 HMAC 签名。解一次，再从 Claims 上取。
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /** 从已验过签名的 claims 取角色；缺声明或空白按 USER（V43 之前签发的 token 没有 role）。 */
    public static String roleOf(Claims claims) {
        Object raw = claims == null ? null : claims.get("role");
        return raw == null || String.valueOf(raw).trim().isEmpty()
                ? AuthContext.ROLE_USER : String.valueOf(raw);
    }

    /** 令牌版本号声明。V43 之前签发的 token 没有 tv → null，调用方按「与当前版本不符」处理。 */
    public static Integer versionOf(Claims claims) {
        Object raw = claims == null ? null : claims.get("tv");
        return raw instanceof Number ? ((Number) raw).intValue() : null;
    }
}
