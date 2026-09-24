package com.aurora.imagehub.model.param;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * 管理员维护模板正文及填写项，不允许传入存储地址或生成参数。
 */
@Getter
@Setter
@NoArgsConstructor
public class CreationTemplateParam {

    @NotBlank
    @Size(max = 80)
    private String title;

    @NotNull
    @Size(max = 300)
    private String description = "";

    @NotNull
    @Positive
    private Long categoryId;

    @NotBlank
    @Size(max = 4000)
    private String promptPattern;

    @NotNull
    @Size(max = 8)
    @Valid
    private List<@NotNull TemplateFieldParam> fields = List.of();

    @NotNull
    @Size(max = 10)
    private List<@NotNull @Positive Long> tagIds = List.of();

    @NotNull
    private Boolean enabled = false;

    @NotNull
    @Min(0)
    @Max(100000)
    private Integer sortOrder = 0;
}
