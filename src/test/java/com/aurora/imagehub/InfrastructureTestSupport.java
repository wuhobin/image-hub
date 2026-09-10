package com.aurora.imagehub;

import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(locations = "classpath:infrastructure-test.properties")
abstract class InfrastructureTestSupport {

    @MockitoBean
    protected RedissonClient redissonClient;

    @MockitoBean
    protected LettuceConnectionFactory redisConnectionFactory;
}
