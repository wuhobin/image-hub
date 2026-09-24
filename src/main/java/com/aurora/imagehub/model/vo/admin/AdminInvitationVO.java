package com.aurora.imagehub.model.vo.admin;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 管理员审核所需的邀请记录；包含注册 IP 和操作留痕，仅限管理接口。
 */
@Getter
@Setter
@NoArgsConstructor
public class AdminInvitationVO {

    private String id;

    private String inviterId;

    private String inviteeId;

    private String inviterName;

    private String inviteeName;

    private String registerIp;

    private Integer inviterPoints;

    private Integer inviteePoints;

    private String status;

    private String riskReason;

    private String reviewerId;

    private String reviewNote;

    private Date createTime;

    private Date updateTime;
}
