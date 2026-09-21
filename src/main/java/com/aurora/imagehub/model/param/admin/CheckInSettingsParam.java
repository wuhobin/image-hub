package com.aurora.imagehub.model.param.admin;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

/**
 * 正整数奖励配置，使用原始精度校验，避免小数静默截断。
 */
@Getter
@Setter
@NoArgsConstructor
public class CheckInSettingsParam {

    @NotNull
    @DecimalMin("1")
    @DecimalMax("2147483647")
    @Digits(integer = 10, fraction = 0)
    private BigDecimal dailyPoints;

    @NotNull
    @DecimalMin("1")
    @DecimalMax("2147483647")
    @Digits(integer = 10, fraction = 0)
    private BigDecimal bonusPoints;
}
