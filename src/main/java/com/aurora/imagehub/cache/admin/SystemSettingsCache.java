package com.aurora.imagehub.cache.admin;

import com.aurora.starter.redis.core.TwoLevelCacheTemplate;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.aurora.imagehub.constants.SystemSettingsConstants.CACHE_KEY_PREFIX;
import static com.aurora.imagehub.constants.SystemSettingsConstants.CACHE_NAME;
import static com.aurora.imagehub.constants.SystemSettingsConstants.CACHE_TTL_DAYS;

/** 只缓存全局配置；复用父项目的提交后刷新与多实例失效广播，不缓存用户消耗。 */
@Component
@RequiredArgsConstructor
public class SystemSettingsCache {

    private final TwoLevelCacheTemplate twoLevelCacheTemplate;

    /** 按配置键读取文本值，未命中时回源；缓存有效期统一为 3 天。 */
    public String get(String configKey, Supplier<String> loader) {
        return twoLevelCacheTemplate.get(CACHE_NAME, CACHE_KEY_PREFIX + configKey, loader, CACHE_TTL_DAYS, TimeUnit.DAYS);
    }

    /** 写库前清理该项缓存；故障向调用方抛出，阻止无法同步缓存的配置修改。 */
    public void prepareUpdate(String configKey) {
        twoLevelCacheTemplate.evictRequired(CACHE_NAME, CACHE_KEY_PREFIX + configKey);
    }

    /** 注册事务提交后的刷新与失效广播；刷新失败由父项目记录，不回滚已提交配置。 */
    public void refreshAfterCommit(String configKey, String configValue) {
        twoLevelCacheTemplate.replaceAfterCommitBestEffort(CACHE_NAME, CACHE_KEY_PREFIX + configKey, configValue, CACHE_TTL_DAYS, TimeUnit.DAYS);
    }
}
