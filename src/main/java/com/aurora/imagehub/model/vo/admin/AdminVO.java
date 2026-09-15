package com.aurora.imagehub.model.vo.admin;

import lombok.*;

/** 管理员公开身份，不包含凭据。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminVO {
    private String id;
    private String username;
}
