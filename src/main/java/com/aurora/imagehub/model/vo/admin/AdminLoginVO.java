package com.aurora.imagehub.model.vo.admin;

import lombok.*;

/** 管理登录结果，不生成包含 Token 的 toString。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminLoginVO {
    private String token;
    private long expiresIn;
    private AdminVO admin;
}
