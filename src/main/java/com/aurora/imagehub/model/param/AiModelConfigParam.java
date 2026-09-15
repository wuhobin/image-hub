package com.aurora.imagehub.model.param;

import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 管理员提交模型配置；空 Key 表示编辑时保留原密钥，不生成 toString。 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelConfigParam {

    @NotBlank @Size(max = 80)
    private String name;

    @NotBlank @Pattern(regexp = "[A-Za-z0-9._:/-]{1,120}")
    private String modelCode;

    @NotBlank @Size(max = 500)
    private String baseUrl;

    @NotBlank @Size(max = 200)
    private String imagesPath = "/v1/images/generations";

    @Size(max = 512)
    private String apiKey;

    @NotEmpty @Size(max = 32)
    private List<@NotBlank @Pattern(regexp = "[0-9]{3,4}x[0-9]{3,4}") String> sizes;

    @NotBlank
    private String defaultSize;

    @NotEmpty @Size(max = 8)
    private List<@NotBlank @Pattern(regexp = "low|medium|high|auto") String> qualities;

    @NotBlank
    private String defaultQuality;

    @NotNull
    private Boolean enabled = false;

    @NotNull @Min(0) @Max(9999)
    private Integer sortOrder = 0;
}
