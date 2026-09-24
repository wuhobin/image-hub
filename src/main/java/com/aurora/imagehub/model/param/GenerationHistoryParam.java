package com.aurora.imagehub.model.param;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 本人创作记录查询；状态与关键词先过滤再分页，省略状态兼容旧客户端的全部记录查询。
 */
@Getter
@Setter
@NoArgsConstructor
public class GenerationHistoryParam {

    @Min(1)
    private int page = 1;

    @Min(1)
    @Max(50)
    private int pageSize = 12;

    @Pattern(regexp = "done|running|failed")
    private String status;

    @Size(max = 200)
    private String keyword;

    @NotBlank
    @Pattern(regexp = "asc|desc")
    private String order = "desc";
}
