package com.emotion.config;

import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 空闲超时：一个会话连续 N 分钟没有「真人在用」的请求就作废，下一次请求直接 401 回登录页。
 *
 * <p>令牌本身的有效期是平的 24 小时（{@code jwt.expiration}），签出去之后不管有没有人用都能撑满，
 * 于是「浏览器忘了关」＝「第二天早上这个 token 还在」。互踢管的是「别人抢了这个号」，
 * 管不到「自己把号晾在那儿」，所以这里补上第二个判据。
 *
 * <p><b>活动表只在内存里，不写库。</b>把「最后活动时间」落到 {@code t_user} 就得每个请求一次 UPDATE，
 * 而 GET 带写正是这平台读侧慢的原因之一（见记忆：慢接口审计）。写在内存里的两个代价如实列出来：
 * <ul>
 *   <li>发版重启＝每个人的空闲窗口重新计（键是 {@code userId + iat}，新 token 的 iat 是新的，
 *       表里查不到就按「刚活动」处理——宁可重开一个窗口，也不在重启时把人踢下线）；</li>
 *   <li>每套 JVM 各有各的钟，本机再起一套后端时两片的计时互不可见。</li>
 * </ul>
 * 鉴权判据（令牌版本号）已经收回库里，跨进程是对的；这条只是外加的活跃度限制，弱一档可以接受。
 *
 * <p>键里带 {@code iat} 还有个顺带的好处：被互踢下去的旧 token 拿着另一个 iat，
 * 在版本号那一关就死了，走不到这里，所以这张表里活着的条目本来就只剩「当前那一次登录」。
 * 表不主动清理：条目数量级是「每天的登录次数」，重启自然清零。
 */
@Component
public class SessionActivityRegistry {

    /**
     * 心跳接口（{@code GET /api/auth/ping}）。<b>它不许刷新活动时间</b>：
     * 心跳是浏览器每 60 秒自己发的，算活动的话空闲窗口永远续满，这条超时形同虚设。
     */
    public static final String PING_URI = "/api/auth/ping";

    private final long idleMillis;
    private final ConcurrentHashMap<String, Long> lastActiveAt = new ConcurrentHashMap<>();

    public SessionActivityRegistry(@Value("${session.idle-timeout-minutes:120}") int minutes) {
        this(minutes <= 0 ? 0L : TimeUnit.MINUTES.toMillis(minutes));
    }

    /** 毫秒粒度的入口留给单测：按分钟没法在测试里等出一个空闲窗口。 */
    SessionActivityRegistry(long idleMillis) {
        this.idleMillis = Math.max(0L, idleMillis);
    }

    /** 关掉空闲超时（配置填 0 或负数）时这里返回 false，一切按原来的平 24 小时行为走。 */
    public boolean isEnabled() {
        return idleMillis > 0;
    }

    /**
     * 这个会话是不是已经晾太久。表里没条目＝第一次见到（刚登录，或刚发完版），
     * 按「刚活动」处理：判活的代价是莫名其妙把人踢下线，这里宁可放宽一次。
     */
    public boolean expired(Long userId, Date issuedAt) {
        if (!isEnabled() || userId == null || issuedAt == null) {
            return false;
        }
        Long seen = lastActiveAt.get(key(userId, issuedAt));
        return seen != null && System.currentTimeMillis() - seen > idleMillis;
    }

    /** 记一次真人发起的请求。心跳与拿不到签发时刻的旧 token 都不记（见 {@link #PING_URI}）。 */
    public void touch(Long userId, Date issuedAt, String requestUri) {
        if (!isEnabled() || userId == null || issuedAt == null) {
            return;
        }
        if (PING_URI.equals(requestUri)) {
            return;
        }
        lastActiveAt.put(key(userId, issuedAt), System.currentTimeMillis());
    }

    private static String key(Long userId, Date issuedAt) {
        return userId + ":" + issuedAt.getTime();
    }
}
