package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String nickname;
    /** 角色：USER=普通用户 / SUPER_ADMIN=超级管理员。常量与判据见 AuthContext。 */
    private String role;
    /** 令牌版本号：每次登录 +1，旧 token 立即失效（单点登录互踢）。 */
    private Integer tokenVersion;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
