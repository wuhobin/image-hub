package com.aurora.imagehub;

import com.aurora.starter.redis.core.TwoLevelCache;
import com.aurora.starter.redis.core.TwoLevelCacheTemplate;
import com.aurora.starter.redis.core.manager.TwoLevelCacheManager;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 保留父项目真实事务回调，仅替换外部缓存读写，测试不连接真实 Redis。 */
@TestConfiguration
public class SettingsCacheTestConfiguration {

    @Bean
    TwoLevelCache twoLevelCache() {
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        resetCache(twoLevelCache);
        return twoLevelCache;
    }

    /** 使用真实模板保留事务提交后回调，缓存管理器仅返回测试替身。 */
    @Bean
    TwoLevelCacheTemplate twoLevelCacheTemplate(TwoLevelCache twoLevelCache) {
        TwoLevelCacheManager twoLevelCacheManager = mock(TwoLevelCacheManager.class);
        when(twoLevelCacheManager.get(anyString())).thenReturn(twoLevelCache);
        return new TwoLevelCacheTemplate(twoLevelCacheManager);
    }

    /** 清除上个测试的交互和故障模拟，使缓存读取默认回源，避免测试间相互影响。 */
    public static void resetCache(TwoLevelCache twoLevelCache) {
        reset(twoLevelCache);
        when(twoLevelCache.get(anyString(), any(Supplier.class), anyLong(), any()))
                .thenAnswer(call -> call.getArgument(1, Supplier.class).get());
    }
}
