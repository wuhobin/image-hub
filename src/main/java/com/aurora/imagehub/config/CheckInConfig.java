package com.aurora.imagehub.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 签到使用服务端时钟，测试可替换固定时钟验证北京时间跨日。
 */
@Configuration
public class CheckInConfig {

    @Bean
    public Clock checkInClock() {
        return Clock.systemUTC();
    }
}
