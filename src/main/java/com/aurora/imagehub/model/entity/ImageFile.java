package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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

    /** 图片来源独立于文件格式；历史图片默认用户上传。 */
    private String sourceType = "UPLOAD";

    private long size;

    private int width;

    private int height;
    /** 完整的存储平台和对象定位信息，用于重启后删除云端文件；不对外返回。 */
    private String storageInfo;
    /** 积分功能上线后成功上传的标记；仅供恢复积分，不对外暴露。 */
    private Integer quotaCharged = 0;

    /** 实际消耗积分；上传固定为 1，AI 使用任务快照，删除后仍用于恢复累计消耗。 */
    private Integer pointsCost = 1;

    /** 数据库生成创建时间；通用写入忽略调用方传入的时间。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;
    /** 数据库在业务字段实际变化时自动维护更新时间。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;
    /** 逻辑删除标记：0 未删除，1 已删除。 */
    @TableLogic
    private Integer deleted = 0;
}
