package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.dto.LoginRequest;
import com.emotion.dto.RegisterRequest;
import com.emotion.exception.BizException;
import com.emotion.entity.User;
import com.emotion.mapper.UserMapper;
import com.emotion.util.AuthContext;
import com.emotion.util.JwtUtil;
import com.emotion.vo.LoginResponse;
import com.emotion.vo.UserVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final SingleSessionService singleSession;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                       SingleSessionService singleSession) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.singleSession = singleSession;
    }

    public LoginResponse register(RegisterRequest req) {
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (existing != null) {
            throw new BizException("用户名已存在");
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname() != null ? req.getNickname() : req.getUsername());
        // 开户一律 USER：要超管走 /api/admin/users 显式指定，别让一个已经收拢起来的
        // 老接口还具备提权能力——它留在那里只是为了兼容，不是第二套开户口径。
        user.setRole(AuthContext.ROLE_USER);
        user.setTokenVersion(1);
        userMapper.insert(user);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), 1, user.getRole());
        return new LoginResponse(token, user.getUsername(), user.getNickname(), user.getRole());
    }

    public LoginResponse login(LoginRequest req) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        // 单点登录：+1 之后，这个账号此前签发的 token（别的浏览器、别的机器）全部失效。
        int version = singleSession.issue(user.getId());
        String role = user.getRole() == null || user.getRole().trim().isEmpty()
                ? AuthContext.ROLE_USER : user.getRole();
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), version, role);
        return new LoginResponse(token, user.getUsername(), user.getNickname(), role);
    }

    /** 当前登录者的档案（含角色）。前端进外壳时拉一次，角色被改过也能立刻生效。 */
    public UserVO me(Long userId) {
        if (userId == null) {
            throw new BizException("未登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("账号不存在或已被删除");
        }
        return UserVO.from(user);
    }

    /** 退出：版本号 +1，让这个 token 立刻失效——不只是前端清 localStorage 那么表面。 */
    public void logout(Long userId) {
        if (userId != null) {
            singleSession.issue(userId);
        }
    }
}
