package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * 积分收支流水；保留奖励及消费快照，删除图片不删除流水或返还积分。
 */
@Getter
@Setter
@TableName("hub_quota_usage")
public class QuotaUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * IMAGE_UPLOAD、AI_GENERATION 为消费，DAILY_CHECK_IN、CHECK_IN_BONUS 为签到，INVITATION_REWARD、INVITEE_REWARD 为邀请奖励。
     */
    private String scene;

    /** 计费图片 ID、签到日期或邀请记录 ID，同一用户、场景及业务编号只能记账一次。 */
    private String bizId;

    /** 实际收支数量，正整数；预占与失败释放不写流水。 */
    private Integer amount;

    /**
     * 金额保持正整数，由方向区分收入与支出，兼容原消费接口。
     */
    private String direction = "EXPENSE";

    private String description;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    @TableLogic
    private Integer deleted = 0;
}
