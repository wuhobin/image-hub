package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/** 登录结果；expiresIn 为当前 Token 的剩余有效秒数。禁止记录 Token。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {
    private String token;

    private long expiresIn;

    private UserVO user;
}
