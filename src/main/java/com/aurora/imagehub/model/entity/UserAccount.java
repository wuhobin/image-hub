package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import java.util.Date;
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
public class UserAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String email;
    private String passwordHash;

    /**
     * 指向本人图片；专用头像不进入图库、不参与积分消费。
     */
    private String avatarImageId;

    /** 数据库生成创建时间；通用写入忽略调用方传入的时间。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;
    /** 数据库在业务字段实际变化时自动维护更新时间。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    /** 逻辑删除标记：0 未删除，1 已删除。 */
    @TableLogic
    private Integer deleted = 0;
}
