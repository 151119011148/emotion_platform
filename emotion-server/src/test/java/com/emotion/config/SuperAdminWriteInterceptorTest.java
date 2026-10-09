package com.emotion.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.emotion.exception.BizException;

/**
 * 写门槛的豁免表是承重的：漏一条就把他的台账编辑口挡死，多一条就把共享业务数据放开。
 * 所以逐条断言，不靠肉眼读那张 List。
 */
class SuperAdminWriteInterceptorTest {

    private final SuperAdminWriteInterceptor interceptor = new SuperAdminWriteInterceptor();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                2L, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private MockHttpServletRequest req(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private boolean allowed(String method, String path) {
        try {
            return interceptor.preHandle(req(method, path), response, new Object());
        } catch (BizException e) {
            return false;
        }
    }

    private boolean blocked(String method, String path) {
        return !allowed(method, path);
    }

    @Test
    void 普通账号不能写业务数据() {
        loginAs("USER");
        for (String path : new String[]{"/api/records", "/api/nodes", "/api/anchors", "/api/leader",
                "/api/themes", "/api/mainline/promote", "/api/theme/stock/bind", "/api/review/save",
                "/api/review/fetch", "/api/waverider/run", "/api/scheduler/jobs/x/run", "/api/records/recalc"}) {
            assertTrue(blocked("POST", path), "应当拦住 POST " + path);
        }
        assertTrue(blocked("PUT", "/api/records/1"));
        assertTrue(blocked("DELETE", "/api/nodes/3"));
    }

    @Test
    void 普通账号的写被拦时给的是业务异常而不是403() {
        loginAs("USER");
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(req("POST", "/api/records"), response, new Object()));
        assertTrue(e.getMessage().contains("超级管理员"), e.getMessage());
        assertTrue(e.getMessage().contains("持仓台账"), "消息要说清台账不归这份数据管：" + e.getMessage());
    }

    @Test
    void 台账是各账号自己的_普通账号能写() {
        loginAs("USER");
        assertDoesNotThrow(() -> interceptor.preHandle(req("PUT", "/api/records/positions"), response, new Object()));
        assertDoesNotThrow(() -> interceptor.preHandle(
                req("POST", "/api/records/positions/123/execute"), response, new Object()));
    }

    @Test
    void 登录登出与已有专属文案的口不误伤() {
        loginAs("USER");
        // 登出必须能用，否则普通账号被拦一次就再也退不掉
        assertDoesNotThrow(() -> interceptor.preHandle(req("POST", "/api/auth/logout"), response, new Object()));
        // 这两个口自己会抛「仅超级管理员可操作账号/打分配置」，通用消息不能盖掉它
        assertDoesNotThrow(() -> interceptor.preHandle(req("POST", "/api/admin/users"), response, new Object()));
        assertDoesNotThrow(() -> interceptor.preHandle(req("POST", "/api/scoring/models"), response, new Object()));
        // POST 皮质的读
        assertDoesNotThrow(() -> interceptor.preHandle(req("POST", "/api/review/ai-draft"), response, new Object()));
    }

    @Test
    void 读与预检一律放行() {
        loginAs("USER");
        assertDoesNotThrow(() -> interceptor.preHandle(req("GET", "/api/records/today"), response, new Object()));
        assertDoesNotThrow(() -> interceptor.preHandle(req("HEAD", "/api/records/today"), response, new Object()));
        assertDoesNotThrow(() -> interceptor.preHandle(req("OPTIONS", "/api/records"), response, new Object()));
        // 非 /api 的容器内部转发不在射程内
        assertDoesNotThrow(() -> interceptor.preHandle(req("POST", "/error"), response, new Object()));
    }

    @Test
    void 超管一切照旧() {
        loginAs("SUPER_ADMIN");
        assertDoesNotThrow(() -> interceptor.preHandle(req("POST", "/api/records"), response, new Object()));
        assertDoesNotThrow(() -> interceptor.preHandle(req("DELETE", "/api/nodes/3"), response, new Object()));
    }

    @Test
    void 没登录也当普通账号拦() {
        SecurityContextHolder.clearContext();
        assertTrue(blocked("POST", "/api/records"));
    }
}
