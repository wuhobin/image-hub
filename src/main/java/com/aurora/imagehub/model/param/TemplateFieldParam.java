package com.aurora.imagehub.model.param;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.*;

/**
 * 受控的文本填写项；字段键只做字面替换，不执行表达式。
 */
@Getter
@Setter
@NoArgsConstructor
public class TemplateFieldParam {

    @NotBlank
    @Pattern(regexp = "[a-z][a-z0-9_]{0,31}")
    private String key;

    @NotBlank
    @Size(max = 40)
    private String label;

    @NotNull
    @Size(max = 300)
    private String example = "";

    @NotNull
    private Boolean required = true;
}
