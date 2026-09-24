package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

/**
 * 邀请活动开关及双方奖励；注册时保存金额快照。
 */
@Getter
@Setter
@NoArgsConstructor
public class InvitationSettingsVO {

    private Boolean enabled;

    private Integer inviterPoints;

    private Integer inviteePoints;
}
