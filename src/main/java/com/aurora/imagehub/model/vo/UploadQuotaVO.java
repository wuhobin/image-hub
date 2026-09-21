package com.aurora.imagehub.model.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 当前登录账号的累计共享积分，剩余积分不包含正在上传的预占。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UploadQuotaVO {
    private int total;

    private int remaining;

    private long used;

    /** AI未完成任务预占，已从remaining扣除，但尚未记入used。 */
    private long reserved;

    public UploadQuotaVO(int total, int remaining, long used) {
        this(total, remaining, used, 0);
    }
}
