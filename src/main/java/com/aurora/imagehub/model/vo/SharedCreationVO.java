package com.aurora.imagehub.model.vo;

import java.util.Date;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 公开作品的字段白名单；不包含账户标识、原参考图、任务请求及供应商信息。
 */
@Getter
@Setter
@NoArgsConstructor
public class SharedCreationVO {

    private String shareId;

    private String shareStatus;

    private String authorName;

    /**
     * 作者当前头像；未设置或图片已删除时为空，不保存发布时的快照。
     */
    private String authorAvatarUrl;

    private String imageUrl;

    private int width;

    private int height;

    private Long modelId;

    private String modelName;

    private String size;

    private String quality;

    private Boolean promptPublic;

    private String prompt;

    private Date publishedTime;
}
