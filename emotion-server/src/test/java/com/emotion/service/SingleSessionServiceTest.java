package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.entity.User;
import com.emotion.mapper.UserMapper;

/**
 * 单点登录的令牌版本号判据。
 *
 * <p>这块以前一条测试都没有，而它守的是「同一个账号同时只能有一个会话」——
 * 三条承重的规矩必须钉住：
 * <ol>
 *   <li><b>判据回库</b>：内存缓存就是第二个真相。本机再起一套后端连同一个库时（这仓库的日常状态），
 *       缓存那份既看不见新登录，也拦不住被踢的旧 token；删账号时它更是直接被留着。</li>
 *   <li><b>账号查不到就按 -1</b>：删行本身就是失效判据。</li>
 *   <li><b>自增交给库里那条 UPDATE</b>：读出来 +1 再写回去，两个并发登录会拿到同一个版本号，
 *       两个会话同时在线，互踢形同虚设。</li>
 * </ol>
 */
class SingleSessionServiceTest {

    private static final Long ID = 2L;

    /** 假账号表：一行 User 加三个计数器，够看清 service 到底打没打库、打了几个动作。 */
    private static final class Fake {
        User row;
        int selects;
        int bumps;
        final List<String> calls = new ArrayList<>();

        UserMapper mapper() {
            return (UserMapper) Proxy.newProxyInstance(
                    UserMapper.class.getClassLoader(),
                    new Class<?>[]{UserMapper.class},
                    (proxy, method, args) -> {
                        calls.add(method.getName());
                        switch (method.getName()) {
                            case "selectById":
                                selects++;
                                return row;
                            case "bumpTokenVersion":
                                bumps++;
                                if (row != null) {
                                    row.setTokenVersion(row.getTokenVersion() + 1);
                                }
                                return row == null ? 0 : 1;
                            case "updateById":
                                // 版本号回写走的正是这条路：一次都不许出现
                                return 1;
                            default:
                                return defaults(method.getReturnType());
                        }
                    });
        }
    }

    private static User row(int version) {
        User u = new User();
        u.setId(ID);
        u.setUsername("gaofeng");
        u.setRole("SUPER_ADMIN");
        u.setTokenVersion(version);
        return u;
    }

    private static Object defaults(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == boolean.class) return false;
        return null;
    }

    @Test
    void 每次判活都回库一次_不留内存缓存() {
        Fake fake = new Fake();
        fake.row = row(6);
        SingleSessionService service = new SingleSessionService(fake.mapper());

        assertEquals(6, service.currentVersion(ID));
        assertEquals(6, service.currentVersion(ID));
        assertEquals(2, fake.selects, "读一次就缓存住，等于给第二套 JVM 留了个永远看不见的旧版本号");
    }

    @Test
    void 别的进程刚自增过_下一次请求就得看到新值() {
        Fake fake = new Fake();
        fake.row = row(6);
        SingleSessionService service = new SingleSessionService(fake.mapper());

        assertEquals(6, service.currentVersion(ID));
        // 模拟另一个 JVM 上的一次登录：库里已经是 7 了，这边不该还认 6
        fake.row = row(7);
        assertEquals(7, service.currentVersion(ID));
    }

    @Test
    void 账号被删_版本号按负一处理_任何token都对不上() {
        Fake fake = new Fake();
        fake.row = null;
        SingleSessionService service = new SingleSessionService(fake.mapper());

        assertEquals(-1, service.currentVersion(ID));
        // 删号之后 issue 已经没意义了（自增影响 0 行），但也不能抛：调用方是删号流程的收尾
        assertEquals(-1, service.issue(ID));
        assertTrue(fake.calls.contains("bumpTokenVersion"));
    }

    @Test
    void 自增走库里那条update_不再把读出来的整行写回去() {
        Fake fake = new Fake();
        fake.row = row(6);
        SingleSessionService service = new SingleSessionService(fake.mapper());

        int next = service.issue(ID);

        assertEquals(7, next, "issue 必须返回自增之后的新值，登录要把它签进 token");
        assertEquals(7, fake.row.getTokenVersion().intValue(), "库里那行确实被自增过了");
        assertEquals(1, fake.bumps);
        assertTrue(!fake.calls.contains("updateById"),
                "写版本号只能用 bumpTokenVersion 那条原子 UPDATE；updateById 是读出来 +1 再写回去，并发下会撞号");
    }

    @Test
    void 没有userId时不打库() {
        Fake fake = new Fake();
        fake.row = row(6);
        SingleSessionService service = new SingleSessionService(fake.mapper());

        assertEquals(-1, service.currentVersion(null));
        assertEquals(-1, service.issue(null));
        assertEquals(0, fake.selects);
        assertEquals(0, fake.bumps);
    }
}
