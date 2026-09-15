package com.aurora.imagehub.model.entity.admin;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/** 独立管理员账号；只能通过受控部署初始化，不参与普通用户注册。 */
@Getter
@Setter
@TableName("hub_admin")
public class AdminAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;
    @TableLogic
    private Integer deleted = 0;
}
