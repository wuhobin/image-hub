package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/** 当前用户的公开账户信息，不包含密码哈希。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {
    private String id;

    private String username;

    private String email;

    private String avatarUrl;
}
