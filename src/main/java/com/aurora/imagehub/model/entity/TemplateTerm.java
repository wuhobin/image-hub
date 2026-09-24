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
@TableName("hub_template_term")
public class TemplateTerm {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String kind;

    private String name;

    private Integer sortOrder;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    @TableLogic
    private Integer deleted = 0;
}
