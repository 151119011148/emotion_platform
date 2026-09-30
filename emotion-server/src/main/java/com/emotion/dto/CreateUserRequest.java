package com.emotion.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

import lombok.Data;

/**
 * 超级管理员建账号的请求体。
 *
 * <p>这是注册入口收拢之后<b>唯一</b>的开户路径——{@code /api/auth/register} 虽然还留着，
 * 但已经要求超管身份，且前端不再有任何入口。
 *
 * <p>role 允许直接指定：把人加成超管是常见动作，建完再改一次纯属多余。
 * 空值由服务层回落 USER，不在这里给默认值——DTO 上的默认值会掩盖「调用方到底传没传」。
 */
@Data
public class CreateUserRequest {

    @NotBlank
    @Size(min = 3, max = 50)
    private String username;

    @NotBlank
    @Size(min = 6, max = 100)
    private String password;

    private String nickname;

    /** USER 或 SUPER_ADMIN；非法值由服务层直接拒绝，不静默回落。 */
    private String role;
}
