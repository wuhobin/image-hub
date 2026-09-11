package com.aurora.imagehub.model.param;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.*;

/** 用户名密码登录参数；禁止将请求内容写入日志。 */
@Getter
@Setter
@NoArgsConstructor
public class LoginParam {
    @NotBlank @Size(min = 3, max = 32)
    private String username;

    @NotBlank @Size(min = 6, max = 72)
    private String password;
}
