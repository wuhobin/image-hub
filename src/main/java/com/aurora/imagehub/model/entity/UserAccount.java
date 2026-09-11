package com.aurora.imagehub.model.entity;

import com.aurora.starter.mybatisplus.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 用户账户实体，对应 hub_user；密码哈希仅用于服务端校验，不向接口暴露。 */
@Getter
@Setter
@TableName("hub_user")
public class UserAccount extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String email;
    private String passwordHash;

    /** 逻辑删除标记：0 未删除，1 已删除。 */
    @TableLogic
    private Integer deleted = 0;
}
