package com.emotion.dto;

import javax.validation.constraints.Size;

import lombok.Data;

/**
 * 改账号的请求体：昵称、密码、角色各自独立可空。
 *
 * <p>三个字段全空是一次合法的「什么都不改」，服务层直接返回当前行——
 * 拿 null 当「不改」而不是「清空」，是因为清空昵称/密码没有任何业务含义，
 * 而空密码等于把账号变成谁都能登。
 */
@Data
public class UpdateUserRequest {

    @Size(max = 50)
    private String nickname;

    /** 不为空即重置密码（管理员改密不需要知道原密码）。 */
    @Size(min = 6, max = 100)
    private String password;

    /** USER 或 SUPER_ADMIN；非法值直接拒绝。 */
    private String role;
}
