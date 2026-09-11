package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 图片记录实体，对应 hub_image；所有查询和删除均需校验所属用户。 */
@Getter
@Setter
@TableName("hub_image")
public class ImageFile {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private String name;
    private String url;
    private String type;
    private long size;
    private int width;
    private int height;
    /** 完整的存储平台和对象定位信息，用于重启后删除云端文件；不对外返回。 */
    private String storageInfo;
    private Instant createdAt;
}
