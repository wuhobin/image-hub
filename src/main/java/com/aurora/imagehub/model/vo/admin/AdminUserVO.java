package com.aurora.imagehub.model.vo.admin;

import java.util.Date;
import lombok.*;

/** 管理端用户列表；剩余额度为空表示 Redis 没有可用记录。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserVO {
    private String id;
    private String username;
    private String email;
    private Date createTime;
    private Integer remaining;
}
