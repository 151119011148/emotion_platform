package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.emotion.dto.UpdateUserRequest;
import com.emotion.entity.User;
import com.emotion.mapper.UserMapper;
import com.emotion.vo.UserVO;

/**
 * 账号管理改账号时，<b>令牌版本号绝不允许由这条全字段更新来回写</b>。
 *
 * <p>{@code updateUser} 是「读出整行 → 改几个字段 → updateById」的形状，MyBatis-Plus 会把
 * 读出来那份快照里所有非 null 字段一起写回去，其中就包括 {@code token_version}。
 * 读与写之间如果有一次登录发生（另一台设备顶下线，或管理员刚点了强制下线），
 * 那次 +1 就被这份旧快照抹平——<b>已经被踢掉的 token 又活了</b>。
 * 版本号只有 {@link SingleSessionService#issue} 一条出路：库里一条原子 UPDATE。
 */
class AdminServiceUpdateTest {

    private static final Long ID = 3L;

    private final List<User> written = new ArrayList<>();
    private int bumps;
    private User row;

    private UserMapper mapper() {
        return (UserMapper) Proxy.newProxyInstance(
                UserMapper.class.getClassLoader(),
                new Class<?>[]{UserMapper.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "selectById":
                            // 每次查询返回一份副本：真实 MyBatis 就是这样，service 在结果对象上
                            // 把 tokenVersion 置成 null，不该回头污染「表里那一行」
                            return copy(row);
                        case "updateById":
                            written.add((User) args[0]);
                            return 1;
                        case "bumpTokenVersion":
                            bumps++;
                            if (row != null) {
                                row.setTokenVersion(row.getTokenVersion() + 1);
                            }
                            return 1;
                        case "selectCount":
                            return 5L;
                        default:
                            Class<?> type = method.getReturnType();
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            if (type == boolean.class) return false;
                            return null;
                    }
                });
    }

    /** 普通账号，版本号 6。 */
    private static User user() {
        User u = new User();
        u.setId(ID);
        u.setUsername("newbie");
        u.setNickname("新人");
        u.setPassword("$2a$10$hash");
        u.setRole("USER");
        u.setTokenVersion(6);
        return u;
    }

    private static User copy(User source) {
        if (source == null) {
            return null;
        }
        User u = new User();
        u.setId(source.getId());
        u.setUsername(source.getUsername());
        u.setNickname(source.getNickname());
        u.setPassword(source.getPassword());
        u.setRole(source.getRole());
        u.setTokenVersion(source.getTokenVersion());
        return u;
    }

    private AdminService service(PasswordEncoder encoder) {
        UserMapper mapper = mapper();
        return new AdminService(mapper, encoder, new SingleSessionService(mapper));
    }

    @Test
    void 只改昵称时写回去的那一行不带token_version() {
        row = user();
        UpdateUserRequest req = new UpdateUserRequest();
        req.setNickname("新人甲");

        UserVO vo = service(mock(PasswordEncoder.class)).updateUser(ID, req);

        assertEquals("新人甲", vo.getNickname());
        assertEquals(1, written.size());
        assertNull(written.get(0).getTokenVersion(),
                "带着读出来的 6 写回去，就会把读写之间那次登录的新版本号抹平——被踢的 token 复活");
        assertEquals(0, bumps, "改个昵称不该把人踢下线");
    }

    @Test
    void 改角色走的是原子自增那一条路() {
        row = user();
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole("SUPER_ADMIN");

        service(mock(PasswordEncoder.class)).updateUser(ID, req);

        assertEquals(1, bumps, "角色变了必须 +1，让他重新登录领新角色声明");
        assertEquals(7, row.getTokenVersion().intValue());
        assertNull(written.get(0).getTokenVersion(), "回写的规矩对每一条改动都成立");
    }

    @Test
    void 重置密码也要踢下线() {
        row = user();
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("$2a$10$newhash");
        UpdateUserRequest req = new UpdateUserRequest();
        req.setPassword("secret123");

        service(encoder).updateUser(ID, req);

        assertEquals(1, bumps, "换密码之后旧 token 必须立刻失效，否则旧口令还能用");
        assertNull(written.get(0).getTokenVersion());
    }
}
