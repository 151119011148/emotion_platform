package com.emotion.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.emotion.entity.User;
import com.emotion.mapper.UserMapper;

/**
 * 单点登录（账号互踢）的令牌版本号。
 *
 * <p>JWT 本身无状态：同一账号在两台机器登录会拿到两个都合法的 token，谁也不会掉线。
 * 这里给每个账号一个单调递增的版本号——登录时 +1 并写进 token 的 {@code tv} 声明，
 * 请求时与账号当前版本号比对，不等就说明「这个号已经不在这台机器上了」。
 *
 * <p><b>版本号一律回库读，不留内存缓存。</b>这里曾经挂着一张 ConcurrentHashMap，
 * 某个账号读过一次就永远用那一份，于是两头都出错：
 * <ul>
 *   <li>两套 JVM 打同一个库时（本机 IDE 起后端连的就是生产库，是这仓库的日常状态），
 *       另一套既看不见新登录那次 +1，也拦不住被踢下来的旧 token——互踢在那个进程里静默失效，
 *       新登录的人反而立刻收到 401，看着像「登录坏了」；</li>
 *   <li>删账号那条路径先 {@code deleteById} 再 {@code issue}，此时 {@code selectById} 已是 null，
 *       早退把缓存里那条旧版本号原样留着，被删的人反而还能继续鉴权到 token 自己过期（24 小时）。</li>
 * </ul>
 * 判据只有一个整数的地方，不能同时存在两个真相。代价是每个已鉴权请求多一次主键 SELECT，
 * 生产上应用与 MySQL 同机（loopback），这个价比「踢不动」便宜得多。
 *
 * <p>刻意不建在线会话表：只需要一个整数就能表达「哪次登录才是最新的」，
 * 会话表反而要在退出、token 过期、进程重启时收拾一堆永远没人删的脏行。
 */
@Component
public class SingleSessionService {

    private final UserMapper userMapper;

    public SingleSessionService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 账号当前生效的版本号。账号不存在（已删除）返回 -1：任何真实版本号都等不上它，
     * 等效于「这个号已签发的 token 全部作废」——删号本身就是最强的失效判据。
     *
     * <p>库抖动抛出的 {@code DataAccessException} 在这里不吞：它的真实语义是「服务暂时不可用」，
     * 若被当成「版本号对不上」，一次 MySQL 抖班会把全员 token 清掉、集体弹回登录页。
     * 调用方 {@code JwtAuthFilter} 按 503 处理。
     */
    public int currentVersion(Long userId) {
        if (userId == null) {
            return -1;
        }
        User user = userMapper.selectById(userId);
        return user == null ? -1 : versionOf(user);
    }

    /**
     * 签发新版本号：登录、退出、改角色、重置密码、被管理员强制下线都走这里。
     * 旧 token 从这一刻起全部失效，持有者下一个请求就是 401。
     *
     * <p>自增交给 {@link UserMapper#bumpTokenVersion} 那条 UPDATE 做在库里，
     * 读回同一个事务里完成——MyBatis 的执行器在任何 update 之后清掉本地缓存，
     * 加上 InnoDB 让自己刚写的行可见，所以这里拿到的必然是本次自增的结果，
     * 不会读到并发者写进去的下一号。账号不存在时自增影响 0 行、读回 null → -1。
     */
    @Transactional(rollbackFor = Exception.class)
    public int issue(Long userId) {
        if (userId == null) {
            return -1;
        }
        userMapper.bumpTokenVersion(userId);
        User user = userMapper.selectById(userId);
        return user == null ? -1 : versionOf(user);
    }

    private static int versionOf(User user) {
        return user.getTokenVersion() == null ? 1 : user.getTokenVersion();
    }
}
