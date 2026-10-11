package com.emotion.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.security.Key;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.emotion.entity.User;
import com.emotion.mapper.UserMapper;
import com.emotion.service.SingleSessionService;
import com.emotion.util.JwtUtil;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

/**
 * 每个请求的登录判据：令牌本身 → 版本号（互踢）→ 空闲窗口，三道关各自的失败出口。
 *
 * <p>承重的是<b>两种「失败」的区别</b>：
 * <ul>
 *   <li>令牌不合法、版本号对不上、晾太久＝「没登录」——不建立认证上下文，也不自己写状态码，
 *       让 {@code SecurityConfig} 的 401 入口去说这句话；</li>
 *   <li>MySQL 抖动＝「服务暂时不可用」——必须给 503 且当场返回。顺着上面那条走的话
 *       前端会把 token 清掉、全员弹回登录页，一次库抖等于全员踢下线。</li>
 * </ul>
 *
 * <p>不起 Spring 容器（{@code @SpringBootTest} 会拿生产库把 Flyway 与 Quartz 一起点着），
 * JwtUtil 的 {@code @Value} 字段用反射塞值，账号表用 Proxy 假实现。
 */
class JwtAuthFilterTest {

    /** 测试专用假密钥，不是任何环境的真值。HS256 要求 ≥32 字节，所以给 64 个 hex 字符。 */
    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final Long ID = 2L;
    private static final String USERNAME = "gaofeng";
    private static final String BUSINESS_URI = "/api/records/range";

    /** 库里那行的版本号：测试靠它模拟「另一台设备登录把号顶掉了」。 */
    private int dbVersion = 6;
    private boolean accountExists = true;
    private boolean dbDown = false;

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() throws Exception {
        jwtUtil = new JwtUtil();
        setField(jwtUtil, "secret", SECRET);
        setField(jwtUtil, "expiration", 600_000L);
        jwtUtil.init();
        dbVersion = 6;
        accountExists = true;
        dbDown = false;
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- 第一道关：令牌本身 ----

    @Test
    void 签名不对的token_不建认证态也不抛异常() throws Exception {
        String forged = jwtUtil.generateToken(ID, USERNAME, 6, "SUPER_ADMIN") + "x";

        assertFalse(alive(forged, BUSINESS_URI),
                "验签失败要被吞成「没登录」；抛出会变成 500，前端只会说成网络错误");
    }

    @Test
    void 已过期的token_不建立认证态() throws Exception {
        setField(jwtUtil, "expiration", -1L);
        String stale = jwtUtil.generateToken(ID, USERNAME, 6, "SUPER_ADMIN");

        assertFalse(alive(stale, BUSINESS_URI));
    }

    @Test
    void V43之前那种没有tv声明的老token_一律不认() throws Exception {
        Key key = Keys.hmacShaKeyFor(SECRET.getBytes());
        String legacy = Jwts.builder()
                .setSubject(USERNAME)
                .claim("userId", ID)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        assertFalse(alive(legacy, BUSINESS_URI),
                "缺 tv 就当版本号对不上：全员重新登录一次，顺带把 role 声明领走");
    }

    // ---- 第二道关：版本号（互踢） ----

    @Test
    void 版本号相等就建立认证态_角色按token声明挂成权限() throws Exception {
        String token = jwtUtil.generateToken(ID, USERNAME, 6, "SUPER_ADMIN");

        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = request(token, BUSINESS_URI);
        jwtFilter(new SessionActivityRegistry(120)).doFilter(
                request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "版本号对得上就该认这个会话");
        assertEquals(ID, auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority())),
                "角色只从 token 拿：库里改了角色会同时 +1 版本号，旧 token 里写着旧角色必须失效");
    }

    @Test
    void 版本号对不上_别的设备把这个号顶掉了() throws Exception {
        String token = jwtUtil.generateToken(ID, USERNAME, 6, "SUPER_ADMIN");
        dbVersion = 7;

        assertFalse(alive(token, BUSINESS_URI),
                "判据必须回库读：内存里缓存过 6 的话，这里会误判成还活着");
    }

    @Test
    void 账号已被删除_查不到行就一律不认() throws Exception {
        String token = jwtUtil.generateToken(ID, USERNAME, 6, "SUPER_ADMIN");
        accountExists = false;

        assertFalse(alive(token, BUSINESS_URI),
                "删号要当场生效，不能等 token 自己过期（以前缓存留着旧版本号就是在等过期）");
    }

    // ---- 第三道关：空闲窗口 ----

    @Test
    void 晾过空闲窗口就作废_真人的请求会续命() throws Exception {
        String token = jwtUtil.generateToken(ID, USERNAME, 6, "SUPER_ADMIN");
        JwtAuthFilter filter = jwtFilter(new SessionActivityRegistry(300L));

        assertTrue(alive(token, filter, BUSINESS_URI), "第一次见到这个会话，按刚活动处理");
        sleep(200);
        assertTrue(alive(token, filter, "/api/tianti/height-range"), "200ms 还在 300ms 窗口内");
        sleep(200);
        assertTrue(alive(token, filter, "/api/nodes"), "上一次真人请求把窗口续上了");
        sleep(400);
        assertFalse(alive(token, filter, BUSINESS_URI), "超过 300ms 没有真人请求，作废");
    }

    @Test
    void 心跳不算活动_否则空闲超时永远触发不了() throws Exception {
        String token = jwtUtil.generateToken(ID, USERNAME, 6, "SUPER_ADMIN");
        JwtAuthFilter filter = jwtFilter(new SessionActivityRegistry(300L));

        assertTrue(alive(token, filter, BUSINESS_URI));
        sleep(200);
        assertTrue(alive(token, filter, SessionActivityRegistry.PING_URI),
                "心跳本身落在窗口内，判活通过");
        sleep(200);
        assertFalse(alive(token, filter, BUSINESS_URI),
                "距上一次真人请求已 400ms > 300ms：心跳若续了命，这里就会通过");
    }

    @Test
    void 空闲超时填0即关闭_一切退回平有效期() throws Exception {
        String token = jwtUtil.generateToken(ID, USERNAME, 6, "SUPER_ADMIN");
        JwtAuthFilter filter = jwtFilter(new SessionActivityRegistry(0));

        assertTrue(alive(token, filter, BUSINESS_URI));
        sleep(50);
        assertTrue(alive(token, filter, BUSINESS_URI));
    }

    // ---- 库抖动不是「没登录」，以及没有头的请求 ----

    @Test
    void 库抖动给503_不能被当成未登录而清掉token() throws Exception {
        String token = jwtUtil.generateToken(ID, USERNAME, 6, "SUPER_ADMIN");
        dbDown = true;
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtFilter(new SessionActivityRegistry(120)).doFilter(request(token, BUSINESS_URI), response, chain);

        assertEquals(503, response.getStatus(),
                "503 才对得上「服务暂时不可用」；回 401/403 前端就会清 token、全员弹回登录页");
        assertNull(chain.getRequest(), "503 当场返回，不许再往下游走");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void 没有Authorization头时_原样放行让安全链出401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BUSINESS_URI);
        request.setRequestURI(BUSINESS_URI);

        jwtFilter(new SessionActivityRegistry(120)).doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "filter 不自己写状态码，交给 SecurityConfig 那个 401 入口");
        assertEquals(200, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ---- 夹具 ----

    /** 用默认 120 分钟窗口跑一遍 filter，返回这次有没有建立认证态。 */
    private boolean alive(String token, String uri) throws Exception {
        return alive(token, jwtFilter(new SessionActivityRegistry(120)), uri);
    }

    private boolean alive(String token, JwtAuthFilter filter, String uri) throws Exception {
        SecurityContextHolder.clearContext();
        filter.doFilter(request(token, uri), new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    private MockHttpServletRequest request(String token, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        // MockHttpServletRequest 的 requestURI 不带上下文，filter 里比较的是这条
        request.setRequestURI(uri);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private JwtAuthFilter jwtFilter(SessionActivityRegistry activity) {
        return new JwtAuthFilter(jwtUtil, new SingleSessionService(userMapper()), activity);
    }

    private UserMapper userMapper() {
        return (UserMapper) Proxy.newProxyInstance(
                UserMapper.class.getClassLoader(),
                new Class<?>[]{UserMapper.class},
                (proxy, method, args) -> {
                    if (dbDown) {
                        throw new DataAccessResourceFailureException("测试桩：库抖了");
                    }
                    if ("selectById".equals(method.getName())) {
                        if (!accountExists) {
                            return null;
                        }
                        User u = new User();
                        u.setId(ID);
                        u.setUsername(USERNAME);
                        u.setRole("SUPER_ADMIN");
                        u.setTokenVersion(dbVersion);
                        return u;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == boolean.class) return false;
                    return null;
                });
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
