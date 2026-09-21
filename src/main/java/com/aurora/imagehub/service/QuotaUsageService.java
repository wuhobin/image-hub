package com.aurora.imagehub.service;

import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.model.entity.QuotaUsage;
import com.aurora.imagehub.model.vo.QuotaUsageVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

/** 额度消耗账本；只记录已成功保存的计费图片，与业务入库共用事务。 */
public interface QuotaUsageService extends IService<QuotaUsage> {

    /** 仅供服务端成功保存路径调用；必须已处于事务中，唯一键防止同业务重复记账。 */
    void recordConsumption(ImageFile image);

    /** 分页读取本人的消耗流水，时间相同按流水编号倒序，避免翻页次序不稳定。 */
    Page<QuotaUsageVO> history(long userId, int page, int pageSize);
}
