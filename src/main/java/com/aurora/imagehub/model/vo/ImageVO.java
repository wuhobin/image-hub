package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import com.aurora.imagehub.model.entity.ImageFile;
import java.util.Date;

/** 图片展示数据，不包含所属用户及内部存储信息。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ImageVO {
    private String id;

    private String name;

    /** 原图地址，用于查看大图和复制外链。 */
    private String url;

    /** 七牛按需缩略图，用于列表展示，不另存文件。 */
    private String preview;

    private String type;

    private long size;

    private int width;

    private int height;

    /** 复用全局 Jackson 日期格式和时区，保持数据库生成的时间值。 */
    private Date createdAt;

    public static ImageVO from(ImageFile image) {
        // 同时限制宽高，避免长图预览仍下载大量像素；超出七牛处理限制时回退原图。
        String preview = image.getUrl() + "?imageView2/2/w/600/h/600/q/75/format/webp/ignore-error/1";
        return new ImageVO(image.getId(), image.getName(), image.getUrl(), preview,
                image.getType(), image.getSize(), image.getWidth(), image.getHeight(), image.getCreateTime());
    }
}
