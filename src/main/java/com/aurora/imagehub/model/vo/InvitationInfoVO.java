package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

/**
 * 邀请入口和注册预览，仅公开邀请码、展示名和活动规则。
 */
@Getter
@Setter
@NoArgsConstructor
public class InvitationInfoVO {

    private String inviteCode;

    private String inviterName;

    private InvitationSettingsVO rewards;
}
