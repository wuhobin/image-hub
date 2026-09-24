package com.aurora.imagehub.model.param;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.*;

/**
 * 分类及标签统一维护，类别创建后不可改变。
 */
@Getter
@Setter
@NoArgsConstructor
public class TemplateTermParam {

    @NotBlank
    @Pattern(regexp = "CATEGORY|TAG")
    private String kind;

    @NotBlank
    @Size(max = 30)
    private String name;

    @NotNull
    @Min(0)
    @Max(100000)
    private Integer sortOrder = 0;
}
