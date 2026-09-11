package com.aurora.imagehub.model.param;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.*;

/** 注册请求参数；密码和验证码仅用于本次校验，不得记录到日志。 */
@Getter
@Setter
@NoArgsConstructor
public class RegisterParam {
    @NotBlank @Size(min = 3, max = 32) @Pattern(regexp = "\\S+")
    private String username;

    @NotBlank @Email @Size(max = 254)
    private String email;

    @NotBlank @Size(min = 6, max = 72)
    private String password;

    @NotBlank @Pattern(regexp = "[0-9]{6}")
    private String code;
}
