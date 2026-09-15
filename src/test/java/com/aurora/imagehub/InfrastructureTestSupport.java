package com.aurora.imagehub;

import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(locations = "classpath:infrastructure-test.properties")
abstract class InfrastructureTestSupport {

    @MockitoBean
    protected com.aurora.imagehub.mapper.admin.AdminAccountMapper adminAccountMapper;

    @MockitoBean
    protected com.aurora.imagehub.mapper.admin.AdminUserMapper adminUserMapper;

    // Infrastructure-only contexts deliberately exclude database auto-configuration.
    @MockitoBean
    protected com.aurora.imagehub.mapper.UserMapper userMapper;

    @MockitoBean
    protected com.aurora.imagehub.mapper.ImageMapper imageMapper;

    @MockitoBean
    protected RedissonClient redissonClient;

    @MockitoBean
    protected LettuceConnectionFactory lettuceConnectionFactory;
}
