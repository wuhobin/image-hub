package com.aurora.imagehub.model.param.admin;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * 邀请奖励配置；原始小数值必须先校验，禁止静默截断。
 */
@Getter
@Setter
@NoArgsConstructor
public class InvitationSettingsParam {

    @NotNull
    private Boolean enabled;

    @NotNull
    @DecimalMin("1")
    @DecimalMax("2147483647")
    @Digits(integer = 10, fraction = 0)
    private BigDecimal inviterPoints;

    @NotNull
    @DecimalMin("1")
    @DecimalMax("2147483647")
    @Digits(integer = 10, fraction = 0)
    private BigDecimal inviteePoints;
}
