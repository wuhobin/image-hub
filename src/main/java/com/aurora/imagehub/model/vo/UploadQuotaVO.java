package com.aurora.imagehub.model.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 当前登录账号的累计上传额度，剩余次数不包含正在上传的预占。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UploadQuotaVO {
    private int total;
    private int remaining;
    private long used;
}
