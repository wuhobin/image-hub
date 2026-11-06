package com.aurora.imagehub.model.param;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 邮箱找回密码参数；验证码仅用于本次消费，不生成包含敏感字段的字符串。
 */
@Getter
@Setter
@NoArgsConstructor
public class ResetPasswordParam {

    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    @NotBlank
    @Pattern(regexp = "[0-9]{6}")
    private String code;

    @NotBlank
    @Size(min = 6, max = 72)
    private String password;
}
