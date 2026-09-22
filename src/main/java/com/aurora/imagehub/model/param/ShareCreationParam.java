package com.aurora.imagehub.model.param;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 作者主动发布或修改提示词可见性；归属与发布资格由服务端校验。
 */
@Getter
@Setter
@NoArgsConstructor
public class ShareCreationParam {

    @NotNull(message = "请选择是否公开提示词")
    private Boolean promptPublic = true;
}
