package com.aurora.imagehub.model.entity.admin;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/** 一条记录对应一个预置配置项；配置值按文本存储，由业务代码解析和校验。 */
@Getter
@Setter
@TableName("hub_settings")
public class SystemSettings {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;

    private String configValue;

    private String description;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    @TableLogic
    private Integer deleted = 0;
}
