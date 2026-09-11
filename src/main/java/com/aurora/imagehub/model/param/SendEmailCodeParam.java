package com.aurora.imagehub.model.param;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.*;

/** 发送注册验证码的请求参数。 */
@Getter
@Setter
@NoArgsConstructor
public class SendEmailCodeParam {
    @NotBlank @Email @Size(max = 254)
    private String email;
}
