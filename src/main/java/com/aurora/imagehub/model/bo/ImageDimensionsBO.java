package com.aurora.imagehub.model.bo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/** 从真实图片内容读取的像素尺寸，用于上传校验和记录入库，不直接作为接口响应。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ImageDimensionsBO {
    private int width;

    private int height;
}
