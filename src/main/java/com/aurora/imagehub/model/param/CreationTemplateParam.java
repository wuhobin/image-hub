package com.aurora.imagehub.model.param;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.URL;

import java.util.List;

/**
 * 管理员维护模板正文、填写项及外部封面 URL，不接受内部存储定位或生成参数。
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

    /**
     * 外部封面仅由浏览器加载；未传或 null 保留原值，空字符串清除配置。
     */
    @Size(max = 2048)
    @URL(regexp = "(?i)(https?://[^\\s]+)?", message = "封面图 URL 必须是有效的 HTTP 或 HTTPS 地址")
    private String coverUrl;

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
