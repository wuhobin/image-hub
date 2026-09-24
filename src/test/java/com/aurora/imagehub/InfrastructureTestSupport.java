package com.aurora.imagehub;

import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(locations = "classpath:infrastructure-test.properties")
@org.springframework.context.annotation.Import(SettingsCacheTestConfiguration.class)
abstract class InfrastructureTestSupport {

    @MockitoBean
    protected com.aurora.imagehub.mapper.AiGenerationMapper aiGenerationMapper;

    @MockitoBean
    protected com.aurora.imagehub.mapper.AiModelConfigMapper aiModelConfigMapper;

    @MockitoBean
    protected com.aurora.imagehub.mapper.admin.SystemSettingsMapper systemSettingsMapper;

    @MockitoBean
    protected com.aurora.imagehub.mapper.admin.AdminAccountMapper adminAccountMapper;

    @MockitoBean
    protected com.aurora.imagehub.mapper.admin.AdminUserMapper adminUserMapper;

    // Infrastructure-only contexts deliberately exclude database auto-configuration.
    @MockitoBean
    protected com.aurora.imagehub.mapper.UserInvitationMapper userInvitationMapper;

    @MockitoBean
    protected com.aurora.imagehub.mapper.UserMapper userMapper;

    @MockitoBean
    protected com.aurora.imagehub.mapper.ImageMapper imageMapper;

    @MockitoBean
    protected com.aurora.imagehub.mapper.QuotaUsageMapper quotaUsageMapper;

    @MockitoBean
    protected com.aurora.imagehub.mapper.UserCheckInMapper userCheckInMapper;

    @MockitoBean
    protected org.springframework.transaction.PlatformTransactionManager platformTransactionManager;

    @MockitoBean
    protected RedissonClient redissonClient;

    @MockitoBean
    protected LettuceConnectionFactory lettuceConnectionFactory;
}
