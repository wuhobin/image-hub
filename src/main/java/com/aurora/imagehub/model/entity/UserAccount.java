package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 用户账户实体，对应 hub_user；密码哈希仅用于服务端校验，不向接口暴露。 */
@Getter
@Setter
@TableName("hub_user")
public class UserAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String email;
    private String passwordHash;
}
