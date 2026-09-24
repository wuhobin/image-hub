package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.util.Date;

import lombok.Getter;
import lombok.Setter;

/**
 * 模板库持久化数据；审计时间由数据库维护，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("hub_creation_template")
public class CreationTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String seedKey;

    private String title;

    private String description;

    private Long categoryId;

    private String promptPattern;

    private String fieldsJson;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String exampleId;

    private String sourceShareId;

    private String sourceAuthor;

    private Boolean enabled;

    private Integer sortOrder;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    @TableLogic
    private Integer deleted = 0;
}
