package com.aurora.imagehub.model.param;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 创建单张生图任务；请求编号在网络重试时保持不变。 */
@Getter
@Setter
@NoArgsConstructor
public class GenerationParam {

    @NotBlank @Pattern(regexp = "[a-fA-F0-9-]{36}")
    private String requestId;

    @NotNull @Positive
    private Long modelId;

    @NotBlank @Size(max = 4000)
    private String prompt;

    @NotBlank @Size(max = 20)
    private String size;

    @NotBlank @Size(max = 20)
    private String quality;
}
