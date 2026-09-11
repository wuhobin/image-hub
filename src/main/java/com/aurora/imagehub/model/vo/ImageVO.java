package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import com.aurora.imagehub.model.entity.ImageFile;
import java.time.Instant;

/** 图片展示数据，不包含所属用户及内部存储信息。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ImageVO {
    private String id;

    private String name;

    private String url;

    private String preview;

    private String type;

    private long size;

    private int width;

    private int height;

    private Instant createdAt;

    public static ImageVO from(ImageFile image) {
        return new ImageVO(image.getId(), image.getName(), image.getUrl(), image.getUrl(),
                image.getType(), image.getSize(), image.getWidth(), image.getHeight(), image.getCreateTime().toInstant());
    }
}
