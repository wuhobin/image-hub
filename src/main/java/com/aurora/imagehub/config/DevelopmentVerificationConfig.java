package com.aurora.imagehub.config;

import com.aurora.starter.verification.support.VerificationCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 开发及测试环境使用固定邮件验证码，便于联调；邮件投递、过期及消费仍由平台处理。
 * prod 优先排除，即使与 dev 或 test 同时启用也不会替换随机生成器。
 */
@Configuration(proxyBeanMethods = false)
@Profile("(dev | test) & !prod")
@ConditionalOnProperty(prefix = "platform.verification.mail", name = "enabled", havingValue = "true")
public class DevelopmentVerificationConfig {

    @Bean
    public VerificationCodeGenerator verificationCodeGenerator() {
        return new VerificationCodeGenerator() {
            @Override
            public String generate(int length) {
                if (length != 6) {
                    throw new IllegalArgumentException("开发测试环境邮件验证码长度必须为 6 位");
                }
                return "123456";
            }
        };
    }
}
