package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.util.Date;

import lombok.Getter;
import lombok.Setter;

/**
 * 注册时固定的直接邀请关系及奖励快照；审核与双方收入必须原子提交，不支持补填或改绑。
 */
@Getter
@Setter
@TableName("hub_user_invitation")
public class UserInvitation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inviterId;

    private Long inviteeId;

    /**
     * 只由服务端取得；仅向管理员提供，用于集中注册审核。
     */
    private String registerIp;

    private Integer inviterPoints;

    private Integer inviteePoints;

    /**
     * PAID 已到账、PENDING 待审核、REJECTED 已拒绝、DISABLED 活动暂停。
     */
    private String status;

    private String riskReason;

    private Long reviewerId;

    private String reviewNote;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    @TableLogic
    private Integer deleted = 0;
}
