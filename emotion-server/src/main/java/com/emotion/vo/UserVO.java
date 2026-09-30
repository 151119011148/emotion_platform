package com.emotion.vo;

import java.time.LocalDateTime;

import com.emotion.entity.User;

import lombok.Data;

/**
 * 账号管理列表的一行。
 *
 * <p>刻意不返回 password（哪怕是哈希）：管理页只需要展示和改，
 * 把哈希摆到列表里除了给撞库提供素材没有任何用处。
 *
 * <p>roleLabel 给中文、role 给机器码——前端表格显示前者、判断权限用后者，
 * 别拿中文当判据：改一次文案就全站失效。
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String role;
    private String roleLabel;
    private LocalDateTime createdAt;

    public static String roleLabelOf(String role) {
        return "SUPER_ADMIN".equals(role) ? "超级管理员" : "普通用户";
    }

    /**
     * 从实体转 VO。角色缺失/空白一律按 USER 处理——这里的用途是「展示与判断权限」，
     * 不是校验：列表里有一行历史脏数据不该让整个账号页打不开。
     * 真正拒绝非法值的地方是 AdminService 的写侧（normalizeRole 会抛 BizException）。
     */
    public static UserVO from(User user) {
        String role = user.getRole() == null || user.getRole().trim().isEmpty()
                ? "USER" : user.getRole().trim().toUpperCase();
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(role);
        vo.setRoleLabel(roleLabelOf(role));
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
