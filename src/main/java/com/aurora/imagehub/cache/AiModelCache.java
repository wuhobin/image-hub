package com.aurora.imagehub.cache;

import com.aurora.imagehub.model.vo.AiModelVO;
import com.aurora.starter.redis.core.TwoLevelCacheTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 缓存前台公开的模型选项；不缓存密钥、管理分页和任务提交时的计费配置。
 */
@Component
@RequiredArgsConstructor
public class AiModelCache {

    private static final String CACHE_NAME = "imageHubModels";

    private static final String CACHE_KEY = "image-hub:models:available";

    private final TwoLevelCacheTemplate twoLevelCacheTemplate;

    /**
     * 未命中或 Redis 故障时由父项目回源，空列表也缓存；使用可序列化的普通集合。
     */
    public List<AiModelVO> get(Supplier<List<AiModelVO>> loader) {
        return twoLevelCacheTemplate.get(CACHE_NAME, CACHE_KEY, () -> new ArrayList<>(loader.get()), 3, TimeUnit.DAYS);
    }

    /**
     * 写库前清理列表，缓存故障时阻止配置变更。
     */
    public void prepareUpdate() {
        twoLevelCacheTemplate.evictRequired(CACHE_NAME, CACHE_KEY);
    }

    /**
     * 提交后再次清理并广播，下次读取加载完整新列表，避免不同模型的并发修改互相覆盖。
     */
    public void evictAfterCommit() {
        twoLevelCacheTemplate.evictAfterCommitBestEffort(CACHE_NAME, CACHE_KEY);
    }
}
