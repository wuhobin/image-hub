package com.aurora.imagehub.constants;

/** 系统配置的预置键及缓存约定，供配置业务与缓存组件统一引用。 */
public final class SystemSettingsConstants {

    /** 所有用户的免费累计上传总积分配置键。 */
    public static final String FREE_UPLOAD_QUOTA = "upload.free-total";

    public static final String CHECK_IN_DAILY_POINTS = "check-in.daily-points";

    public static final String CHECK_IN_BONUS_POINTS = "check-in.bonus-points";

    public static final String INVITATION_ENABLED = "invitation.enabled";

    public static final String INVITATION_INVITER_POINTS = "invitation.inviter-points";

    public static final String INVITATION_INVITEE_POINTS = "invitation.invitee-points";

    /** 父项目二级缓存实例名，与 application.yml 中的实例配置保持一致。 */
    public static final String CACHE_NAME = "imageHubSettings";

    /** 按配置键独立缓存系统配置。 */
    public static final String CACHE_KEY_PREFIX = "image-hub:settings:";

    /** 配置缓存有效期，单位为天。 */
    public static final long CACHE_TTL_DAYS = 3;

    private SystemSettingsConstants() {
    }
}
