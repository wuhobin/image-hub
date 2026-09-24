package com.aurora.imagehub.service.impl.admin;

import com.aurora.imagehub.cache.admin.SystemSettingsCache;
import com.aurora.imagehub.mapper.admin.SystemSettingsMapper;
import com.aurora.imagehub.model.entity.admin.SystemSettings;
import com.aurora.imagehub.model.vo.admin.AdminSettingsVO;
import com.aurora.imagehub.model.vo.admin.CheckInSettingsVO;
import com.aurora.imagehub.model.vo.InvitationSettingsVO;

import java.util.Map;
import java.util.stream.Collectors;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.imagehub.service.admin.SystemSettingsService;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.aurora.imagehub.constants.SystemSettingsConstants.FREE_UPLOAD_QUOTA;
import static com.aurora.imagehub.constants.SystemSettingsConstants.CHECK_IN_DAILY_POINTS;
import static com.aurora.imagehub.constants.SystemSettingsConstants.CHECK_IN_BONUS_POINTS;
import static com.aurora.imagehub.constants.SystemSettingsConstants.INVITATION_ENABLED;
import static com.aurora.imagehub.constants.SystemSettingsConstants.INVITATION_INVITER_POINTS;
import static com.aurora.imagehub.constants.SystemSettingsConstants.INVITATION_INVITEE_POINTS;

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

    /**
     * 发奖规则沿用配置二级缓存，已领取金额不随配置变化。
     */
    @Override
    public CheckInSettingsVO checkInRewards() {
        return new CheckInSettingsVO(positivePoints(getValue(CHECK_IN_DAILY_POINTS)), positivePoints(getValue(CHECK_IN_BONUS_POINTS)));
    }

    /**
     * 后台展示始终读取数据库的真实配置。
     */
    @Override
    public CheckInSettingsVO checkInSettings() {
        adminAccountService.currentAdmin();
        return new CheckInSettingsVO(positivePoints(requireSettings(CHECK_IN_DAILY_POINTS).getConfigValue()),
                positivePoints(requireSettings(CHECK_IN_BONUS_POINTS).getConfigValue()));
    }

    /**
     * 两个奖励金额在同一事务内保存，缓存故障的处理与基础积分设置一致。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckInSettingsVO updateCheckInSettings(int dailyPoints, int bonusPoints) {
        adminAccountService.currentAdmin();
        if (dailyPoints < 1 || bonusPoints < 1) throw new BizException(400, "签到奖励必须为正整数");
        SystemSettings daily = requireSettings(CHECK_IN_DAILY_POINTS);
        SystemSettings bonus = requireSettings(CHECK_IN_BONUS_POINTS);
        try {
            systemSettingsCache.prepareUpdate(CHECK_IN_DAILY_POINTS);
            systemSettingsCache.prepareUpdate(CHECK_IN_BONUS_POINTS);
        } catch (RuntimeException e) {
            throw new BizException(503, "配置缓存暂时不可用，未保存，请稍后重试", e);
        }
        daily.setConfigValue(Integer.toString(dailyPoints));
        bonus.setConfigValue(Integer.toString(bonusPoints));
        if (systemSettingsMapper.updateById(daily) != 1 || systemSettingsMapper.updateById(bonus) != 1) {
            throw new BizException(409, "配置保存失败，请刷新后重试");
        }
        systemSettingsCache.refreshAfterCommit(CHECK_IN_DAILY_POINTS, daily.getConfigValue());
        systemSettingsCache.refreshAfterCommit(CHECK_IN_BONUS_POINTS, bonus.getConfigValue());
        return new CheckInSettingsVO(dailyPoints, bonusPoints);
    }

    /**
     * 三项规则由同一查询读取，避免并发调价得到不同版本的双方金额；总开关无需等待缓存过期。
     */
    @Override
    public InvitationSettingsVO invitationRewards() {
        Map<String, String> values = list(Wrappers.<SystemSettings>lambdaQuery()
                .in(SystemSettings::getConfigKey, INVITATION_ENABLED, INVITATION_INVITER_POINTS, INVITATION_INVITEE_POINTS))
                .stream().collect(Collectors.toMap(SystemSettings::getConfigKey, SystemSettings::getConfigValue));
        if (values.size() != 3 || !java.util.Set.of("true", "false").contains(values.get(INVITATION_ENABLED))) {
            throw new BizException(503, "邀请配置缺失或无效，请联系管理员");
        }
        InvitationSettingsVO result = new InvitationSettingsVO();
        result.setEnabled(Boolean.parseBoolean(values.get(INVITATION_ENABLED)));
        result.setInviterPoints(positivePoints(values.get(INVITATION_INVITER_POINTS)));
        result.setInviteePoints(positivePoints(values.get(INVITATION_INVITEE_POINTS)));
        return result;
    }

    /**
     * 管理端与注册读取同一份数据库配置。
     */
    @Override
    public InvitationSettingsVO invitationSettings() {
        adminAccountService.currentAdmin();
        return invitationRewards();
    }

    /**
     * 三项在同一事务内更新；邀请配置直接读库，无需写入二级缓存。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvitationSettingsVO updateInvitationSettings(boolean enabled, int inviterPoints, int inviteePoints) {
        adminAccountService.currentAdmin();
        if (inviterPoints < 1 || inviteePoints < 1) throw new BizException(400, "邀请奖励必须为正整数");
        // 固定更新顺序，多个管理员并发保存时避免反向加锁。
        String[] keys = {INVITATION_ENABLED, INVITATION_INVITER_POINTS, INVITATION_INVITEE_POINTS};
        String[] values = {Boolean.toString(enabled), Integer.toString(inviterPoints), Integer.toString(inviteePoints)};
        for (int index = 0; index < keys.length; index++) {
            SystemSettings settings = requireSettings(keys[index]);
            settings.setConfigValue(values[index]);
            if (systemSettingsMapper.updateById(settings) != 1)
                throw new BizException(409, "配置保存失败，请刷新后重试");
        }
        return invitationRewards();
    }

    /**
     * 数据库误配置不能产生零积分或负积分奖励。
     */
    private int positivePoints(String value) {
        int points = parseFreeUploadQuota(value);
        if (points == 0) throw new BizException(503, "奖励积分配置无效，请联系管理员");
        return points;
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
