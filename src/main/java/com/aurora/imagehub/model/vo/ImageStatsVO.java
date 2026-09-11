package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

/** 当前用户全部图片的统计，与列表筛选及分页独立。 */
@Getter
@Setter
@NoArgsConstructor
public class ImageStatsVO {
    private long totalCount;

    private long totalBytes;
}
