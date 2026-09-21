package com.aurora.imagehub.service.impl.admin;

import com.aurora.imagehub.cache.admin.SystemSettingsCache;
import com.aurora.imagehub.mapper.admin.SystemSettingsMapper;
import com.aurora.imagehub.model.entity.admin.SystemSettings;
import com.aurora.imagehub.model.vo.admin.AdminSettingsVO;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.imagehub.service.admin.SystemSettingsService;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.aurora.imagehub.constants.SystemSettingsConstants.FREE_UPLOAD_QUOTA;

/** 按配置键读取全局设置；管理接口只更新代码预置的配置项，不开放任意键值写入。 */
@Service
@RequiredArgsConstructor
public class SystemSettingsServiceImpl extends ServiceImpl<SystemSettingsMapper, SystemSettings> implements SystemSettingsService {

    private final SystemSettingsMapper systemSettingsMapper;

    private final SystemSettingsCache systemSettingsCache;

    private final AdminAccountService adminAccountService;

    /** 内部业务按配置键复用二级缓存，未命中时读取有效的数据库记录。 */
    @Override
    public String getValue(String configKey) {
        return systemSettingsCache.get(configKey, () -> requireSettings(configKey).getConfigValue());
    }

    /** 将通用文本配置解析为积分，确保业务不会使用非法的负数或非整数积分。 */
    @Override
    public int freeUploadQuota() {
        return parseFreeUploadQuota(getValue(FREE_UPLOAD_QUOTA));
    }

    /** 管理页面直接回读数据库，避免展示缓存中尚未同步的配置。 */
    @Override
    public AdminSettingsVO settings() {
        adminAccountService.currentAdmin();
        return response(requireSettings(FREE_UPLOAD_QUOTA));
    }

    /**
     * 修改全局免费总积分，不重置用户累计消耗。
     * 写库前清理缓存失败则拒绝保存；提交后刷新失败不撤销数据库修改。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminSettingsVO updateSettings(int freeUploadQuota) {
        adminAccountService.currentAdmin();
        if (freeUploadQuota < 0) {
            throw new BizException(400, "免费总积分不能小于 0");
        }
        SystemSettings settings = requireSettings(FREE_UPLOAD_QUOTA);

        // 预先拒绝已知缓存故障，避免明知无法同步仍修改全局积分。
        try {
            systemSettingsCache.prepareUpdate(FREE_UPLOAD_QUOTA);
        } catch (RuntimeException e) {
            throw new BizException(503, "配置缓存暂时不可用，未保存，请稍后重试", e);
        }

        settings.setConfigValue(Integer.toString(freeUploadQuota));
        if (systemSettingsMapper.updateById(settings) != 1) {
            throw new BizException(409, "配置保存失败，请刷新后重试");
        }
        systemSettingsCache.refreshAfterCommit(FREE_UPLOAD_QUOTA, settings.getConfigValue());

        // 时间由数据库维护，回读结果；提交后回调由父项目负责执行。
        return response(requireSettings(FREE_UPLOAD_QUOTA));
    }

    /** 只读取未逻辑删除的预置配置；缺失或空值返回 503，避免默认值掩盖配置故障。 */
    private SystemSettings requireSettings(String configKey) {
        SystemSettings settings = systemSettingsMapper.selectOne(Wrappers.<SystemSettings>lambdaQuery()
                .eq(SystemSettings::getConfigKey, configKey));
        if (settings == null || settings.getConfigValue() == null) {
            throw new BizException(503, "平台配置缺失，请联系管理员");
        }
        return settings;
    }

    /** 组装管理响应并验证积分类型，更新时间使用数据库回读值。 */
    private AdminSettingsVO response(SystemSettings settings) {
        return new AdminSettingsVO(parseFreeUploadQuota(settings.getConfigValue()), settings.getUpdateTime());
    }

    /** 文本必须能解析为非负 int，非整数、负数及溢出统一视为配置故障。 */
    private int parseFreeUploadQuota(String value) {
        try {
            int total = Integer.parseInt(value);
            if (total >= 0) {
                return total;
            }
        } catch (NumberFormatException ignored) {
            // 数据库存储支持多种配置类型，积分仍必须验证为非负整数。
        }
        throw new BizException(503, "平台积分配置无效，请联系管理员");
    }
}
