package com.aurora.imagehub.model.param.admin;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.*;

/** 只接受非负整数额度；先校验原始数值，避免 JSON 小数被静默截断。 */
@Getter
@Setter
@NoArgsConstructor
public class AdminSettingsParam {

    @NotNull(message = "请填写免费总额度")
    @DecimalMin(value = "0", message = "免费总额度不能小于 0")
    @DecimalMax(value = "2147483647", message = "免费总额度过大")
    @Digits(integer = 10, fraction = 0, message = "免费总额度必须为整数")
    private BigDecimal freeUploadQuota;
}
