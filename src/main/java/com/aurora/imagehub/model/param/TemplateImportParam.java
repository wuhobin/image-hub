package com.aurora.imagehub.model.param;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.*;

/**
 * 仅允许按公开分享编号导入，不接受任意图片地址。
 */
@Getter
@Setter
@NoArgsConstructor
public class TemplateImportParam {

    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F-]{36}")
    private String shareId;

    @NotBlank
    @Size(max = 80)
    private String title;

    @NotNull
    @Positive
    private Long categoryId;
}
