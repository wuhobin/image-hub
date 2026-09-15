package com.aurora.imagehub.service.admin;

import com.aurora.imagehub.model.entity.admin.SystemSettings;
import com.aurora.imagehub.model.vo.admin.AdminSettingsVO;
import com.baomidou.mybatisplus.extension.service.IService;

/** 全局配置：后台读写验证管理员，额度业务内部读取复用二级缓存。 */
public interface SystemSettingsService extends IService<SystemSettings> {

    /** 服务端按预置配置键读取文本值；调用方负责对应类型的解析，不直接暴露给客户端。 */
    String getValue(String configKey);

    /** 读取缓存中的全局免费总额度；缺失或非法配置应报错，不静默使用默认值。 */
    int freeUploadQuota();

    /** 验证当前管理员后直接读取数据库，返回配置值及数据库维护的更新时间。 */
    AdminSettingsVO settings();

    /** 验证管理员并保存非负额度；保存前缓存故障终止写入，提交后的刷新失败不回滚数据库。 */
    AdminSettingsVO updateSettings(int freeUploadQuota);
}
