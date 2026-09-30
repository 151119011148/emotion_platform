package com.emotion.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String username;
    private String nickname;
    /** USER / SUPER_ADMIN。前端据此决定要不要显示「账号管理」入口。 */
    private String role;
}
