package com.aurora.imagehub.model.param;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 从本人图库选择头像，仅接收图片编号，不接受客户端提供的外部地址。
 */
@Getter
@Setter
@NoArgsConstructor
public class AvatarParam {

    @NotBlank
    @Size(max = 36)
    private String imageId;
}
