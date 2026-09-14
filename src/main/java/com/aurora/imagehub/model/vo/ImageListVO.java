package com.aurora.imagehub.model.vo;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 图片列表及同一筛选条件下的总大小；分页与总数复用 MyBatis-Plus Page。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ImageListVO {
    private Page<ImageVO> page;

    /** 全部匹配图片的大小，单位字节，不限于当前页。 */
    private long totalBytes;
}
