package com.emotion.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.dto.CreateUserRequest;
import com.emotion.dto.UpdateUserRequest;
import com.emotion.entity.User;
import com.emotion.exception.BizException;
import com.emotion.mapper.UserMapper;
import com.emotion.util.AuthContext;
import com.emotion.vo.UserVO;

/**
 * 账号管理：只有 {@code SUPER_ADMIN} 能碰的那一组动作（建号 / 改昵称 / 改角色 /
 * 重置密码 / 强制下线 / 删号）。
 *
 * <p>权限判据只有一处——{@link AuthContext#isSuperAdmin()}，来源是登录时签进 token 的角色声明。
 * Controller 那边也判一次，但那是给前端一个说得清的 403 文案，不是第二道真相。
 *
 * <p>两条自锁护栏（这是账号管理最容易把自己关在门外的地方）：
 * <ul>
 *   <li>不能删自己、不能把自己降成 USER——手滑一次就再没有超管能进来救；</li>
 *   <li>不能让库里最后一个超管消失（删、降都算）。</li>
 * </ul>
 *
 * <p>改角色与重置密码都会调 {@link SingleSessionService#issue} 把令牌版本号 +1：
 * 对方必须重新登录才能领到新角色/新密码，否则旧 token 里的角色声明还写着旧值，
 * 「库里是 USER、token 说是超管」就是一场查不出来的越权。
 */
@Service
public class AdminService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SingleSessionService singleSession;

    public AdminService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                        SingleSessionService singleSession) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.singleSession = singleSession;
    }

    /** 账号列表，按 id 升序（建号顺序即展示顺序，新号在最下面）。 */
    public List<UserVO> listUsers() {
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>().orderByAsc(User::getId));
        List<UserVO> out = new ArrayList<>(users.size());
        for (User u : users) {
            out.add(UserVO.from(u));
        }
        return out;
    }

    public UserVO createUser(CreateUserRequest req) {
        String username = req.getUsername() == null ? "" : req.getUsername().trim();
        if (username.isEmpty()) {
            throw new BizException("用户名不能为空");
        }
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (existing != null) {
            throw new BizException("用户名已存在：" + username);
        }

        String role = normalizeRole(req.getRole());
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(StringUtils.hasText(req.getNickname()) ? req.getNickname().trim() : username);
        user.setRole(role);
        user.setTokenVersion(1);
        userMapper.insert(user);
        return UserVO.from(user);
    }

    public UserVO updateUser(Long id, UpdateUserRequest req) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException("账号不存在：id=" + id);
        }

        boolean roleChanged = false;
        if (StringUtils.hasText(req.getRole())) {
            String role = normalizeRole(req.getRole());
            roleChanged = !role.equals(normalizeRole(user.getRole()));
            if (roleChanged && isSelf(id) && "SUPER_ADMIN".equals(normalizeRole(user.getRole()))) {
                throw new BizException("不能把自己降级：最后一个超级管理员至少要留一个");
            }
            if (roleChanged && isLastSuperAdmin(user) && !"SUPER_ADMIN".equals(role)) {
                throw new BizException("至少要保留一个超级管理员");
            }
            user.setRole(role);
        }
        if (StringUtils.hasText(req.getNickname())) {
            user.setNickname(req.getNickname().trim());
        }
        boolean passwordChanged = StringUtils.hasText(req.getPassword());
        if (passwordChanged) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }

        userMapper.updateById(user);
        // 改完立刻踢下线：让他重新登录拿新角色/新密码，别让旧 token 继续用旧身份。
        if (roleChanged || passwordChanged) {
            singleSession.issue(id);
        }
        return UserVO.from(user);
    }

    public void deleteUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException("账号不存在：id=" + id);
        }
        if (isSelf(id)) {
            throw new BizException("不能删除自己：删掉就没有超级管理员能进来了");
        }
        if (isLastSuperAdmin(user)) {
            throw new BizException("至少要保留一个超级管理员");
        }
        userMapper.deleteById(id);
        // 账号都没了，版本号缓存留着只是让同名 id 复用时捡到旧值
        singleSession.issue(id);
    }

    /** 强制下线：版本号 +1，对方下一个请求就是 401。账号本身不动。 */
    public void kickUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException("账号不存在：id=" + id);
        }
        singleSession.issue(id);
    }

    private boolean isSelf(Long id) {
        Long me = AuthContext.currentUserId();
        return me != null && me.equals(id);
    }

    /** 当前这个账号是不是库里仅剩的那一个超管（按库里的值判断，不按 token）。 */
    private boolean isLastSuperAdmin(User user) {
        if (!"SUPER_ADMIN".equals(normalizeRole(user.getRole()))) {
            return false;
        }
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, AuthContext.ROLE_SUPER_ADMIN));
        return count != null && count <= 1;
    }

    private static String normalizeRole(String raw) {
        if (!StringUtils.hasText(raw)) {
            return AuthContext.ROLE_USER;
        }
        String role = raw.trim().toUpperCase();
        if (AuthContext.ROLE_SUPER_ADMIN.equals(role) || AuthContext.ROLE_USER.equals(role)) {
            return role;
        }
        throw new BizException("角色只能是 USER 或 SUPER_ADMIN，收到：" + raw);
    }

}
