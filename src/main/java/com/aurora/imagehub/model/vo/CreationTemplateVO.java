package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Date;

/**
 * 模板的公开内容白名单，永不返回内部存储定位。
 */
@Getter
@Setter
@NoArgsConstructor
public class CreationTemplateVO {

    private Long id;

    private String title;

    private String description;

    private TemplateTermVO category;

    private List<TemplateTermVO> tags;

    private String promptPattern;

    private List<TemplateFieldVO> fields;

    private String exampleUrl;

    private String sourceShareId;

    private String sourceAuthor;

    private Boolean enabled;

    private Integer sortOrder;

    private Date updateTime;
}
