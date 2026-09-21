package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/** 实际积分消耗流水；独立保留业务快照，删除图片不删除流水或返还积分。 */
@Getter
@Setter
@TableName("hub_quota_usage")
public class QuotaUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** IMAGE_UPLOAD 图片上传；AI_GENERATION AI 创作。 */
    private String scene;

    /** 上传图片 ID 或 AI 任务 ID，同一用户、场景及业务编号只能记账一次。 */
    private String bizId;

    /** 实际消耗数量，正整数；预占与失败释放不写流水。 */
    private Integer amount;

    private String description;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    @TableLogic
    private Integer deleted = 0;
}
