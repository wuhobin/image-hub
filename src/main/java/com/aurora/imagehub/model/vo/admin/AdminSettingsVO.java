package com.aurora.imagehub.model.vo.admin;

import java.util.Date;
import lombok.*;

/** 管理页面展示的配置及数据库更新时间。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminSettingsVO {

    private int freeUploadQuota;

    private Date updateTime;
}
