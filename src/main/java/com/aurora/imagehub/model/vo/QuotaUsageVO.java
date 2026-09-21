package com.aurora.imagehub.model.vo;

import com.aurora.imagehub.model.entity.QuotaUsage;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

/** 个人中心积分流水，不包含用户标识及内部存储定位。 */
@Getter
@Setter
@NoArgsConstructor
public class QuotaUsageVO {

    private Long id;

    private String scene;

    private String bizId;

    private Integer amount;

    private String direction;

    private String description;

    private Date createTime;

    /** 只返回展示与追溯业务所需字段。 */
    public static QuotaUsageVO from(QuotaUsage usage) {
        QuotaUsageVO result = new QuotaUsageVO();
        result.setId(usage.getId());
        result.setScene(usage.getScene());
        result.setBizId(usage.getBizId());
        result.setAmount(usage.getAmount());
        result.setDirection(usage.getDirection());
        result.setDescription(usage.getDescription());
        result.setCreateTime(usage.getCreateTime());
        return result;
    }
}
