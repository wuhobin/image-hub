package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;


/**
 * 模板填写项及示例值。
 */
@Getter
@Setter
@NoArgsConstructor
public class TemplateFieldVO {

    private String key;

    private String label;

    private String example;

    private Boolean required;
}
