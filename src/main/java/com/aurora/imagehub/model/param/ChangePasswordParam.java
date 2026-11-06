package com.aurora.imagehub.model.param;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 登录后改密参数；账户身份由登录态提供，不接受客户端用户编号。
 */
@Getter
@Setter
@NoArgsConstructor
public class ChangePasswordParam {

    @NotBlank
    @Size(min = 6, max = 72)
    private String currentPassword;

    @NotBlank
    @Size(min = 6, max = 72)
    private String password;
}
