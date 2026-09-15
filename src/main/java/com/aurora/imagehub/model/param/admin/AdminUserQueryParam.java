package com.aurora.imagehub.model.param.admin;

import jakarta.validation.constraints.*;
import lombok.*;

/** 管理端只读查询参数，限制分页大小及搜索长度。 */
@Getter
@Setter
@NoArgsConstructor
public class AdminUserQueryParam {
    @Size(max = 254)
    private String search = "";
    @Min(1)
    private int page = 1;
    @Min(1) @Max(100)
    private int pageSize = 20;
}
