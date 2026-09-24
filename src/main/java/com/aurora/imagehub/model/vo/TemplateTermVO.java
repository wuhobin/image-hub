package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;


/**
 * 公开分类和标签。
 */
@Getter
@Setter
@NoArgsConstructor
public class TemplateTermVO {

    private Long id;

    private String kind;

    private String name;

    private Integer sortOrder;
}
