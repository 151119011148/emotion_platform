package com.emotion.service;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.emotion.entity.User;
import com.emotion.mapper.UserMapper;

/**
 * 单点登录（账号互踢）的令牌版本号。
 *
 * <p>JWT 本身无状态：同一账号在两台机器登录会拿到两个都合法的 token，谁也不会掉线。
 * 这里给每个账号一个单调递增的版本号——登录时 +1 并写进 token 的 {@code tv} 声明，
 * 请求时与账号当前版本号比对，不等就说明「这个号已经不在这台机器上了」。
 *
 * <p>版本号落库（{@code t_user.token_version}）而不是只放内存：进程重启后旧 token
 * 仍然要对得上，否则一次发布就等于全员免检放行。内存那层只是缓存，
 * 免得每个请求都打一次库。
 *
 * <p>刻意不建在线会话表：只需要一个整数就能表达「哪次登录才是最新的」，
 * 会话表反而要在退出、token 过期、进程重启时收拾一堆永远没人删的脏行。
 */
@Component
public class SingleSessionService {

    private final UserMapper userMapper;
    private final ConcurrentHashMap<Long, Integer> versions = new ConcurrentHashMap<>();

    public SingleSessionService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 账号当前生效的版本号。缓存里没有时回库读一次并回填——进程刚起来、
     * 或这个账号是第一次发请求时走这条路径。
     * 账号已被删除时返回 -1，任何真实版本号都不会等于它，等效于「一律踢掉」。
     */
    public int currentVersion(Long userId) {
        if (userId == null) {
            return -1;
        }
        Integer cached = versions.get(userId);
        if (cached != null) {
            return cached;
        }
        User user = userMapper.selectById(userId);
        int version = user == null ? -1 : versionOf(user);
        if (version >= 0) {
            versions.putIfAbsent(userId, version);
        }
        return version;
    }

    /**
     * 签发新版本号：登录、改角色、重置密码、被管理员强制下线都走这里。
     * 旧 token 从这一刻起全部失效，持有者下一个请求就会收到 401。
     */
    public int issue(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return -1;
        }
        int next = versionOf(user) + 1;
        User patch = new User();
        patch.setId(userId);
        patch.setTokenVersion(next);
        userMapper.updateById(patch);
        versions.put(userId, next);
        return next;
    }

    private static int versionOf(User user) {
        return user.getTokenVersion() == null ? 1 : user.getTokenVersion();
    }
}
