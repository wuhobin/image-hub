package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 当前用户参与的邀请记录，仅返回本人的奖励，不泄露邮箱和注册 IP。
 */
@Getter
@Setter
@NoArgsConstructor
public class InvitationVO {

    private String id;

    private String counterpartyName;

    private Boolean inviter;

    private Integer points;

    private String status;

    private Date createTime;
}
