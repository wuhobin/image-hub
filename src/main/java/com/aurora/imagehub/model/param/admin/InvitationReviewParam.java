package com.aurora.imagehub.model.param.admin;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.*;

/**
 * 审核结论及备注；邀请编号来自路径，审核人来自可信管理员登录态。
 */
@Getter
@Setter
@NoArgsConstructor
public class InvitationReviewParam {

    @NotNull
    private Boolean approved;

    @Size(max = 255)
    private String note;
}
