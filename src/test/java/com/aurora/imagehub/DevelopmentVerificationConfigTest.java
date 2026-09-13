package com.aurora.imagehub;

import com.aurora.imagehub.config.DevelopmentVerificationConfig;
import com.aurora.starter.redis.core.RedisCache;
import com.aurora.starter.verification.config.VerificationAutoConfiguration;
import com.aurora.starter.verification.mail.MailVerificationService;
import com.aurora.starter.verification.support.VerificationCodeGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 验证固定验证码只用于开发测试，并确认平台邮件服务实际采用所选生成器。 */
class DevelopmentVerificationConfigTest {
    private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VerificationAutoConfiguration.class))
            .withUserConfiguration(DevelopmentVerificationConfig.class)
            .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
            .withBean(RedisCache.class, () -> mock(RedisCache.class))
            .withPropertyValues("platform.verification.mail.enabled=true",
                    "platform.verification.mail.from=noreply@example.test");

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "dev,test"})
    void usesFixedCodeForDevelopmentAndTest(String profiles) {
        applicationContextRunner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
            assertThat(context).hasSingleBean(VerificationCodeGenerator.class);
            VerificationCodeGenerator verificationCodeGenerator = context.getBean(VerificationCodeGenerator.class);
            assertThat(verificationCodeGenerator.generate(6)).isEqualTo("123456");
            assertThat(ReflectionTestUtils.getField(context.getBean(MailVerificationService.class), "codeGenerator"))
                    .isSameAs(verificationCodeGenerator);
            assertThatThrownBy(() -> verificationCodeGenerator.generate(8)).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev,prod", "test,prod", "dev,test,prod", "default", "staging"})
    void keepsPlatformRandomGeneratorOutsideDevelopmentAndTest(String profiles) {
        applicationContextRunner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
            assertThat(context).hasSingleBean(VerificationCodeGenerator.class);
            assertThat(context.getBean(VerificationCodeGenerator.class)).isExactlyInstanceOf(VerificationCodeGenerator.class);
        });
    }

    @Test
    void keepsPlatformRandomGeneratorWhenNoProfileIsSelected() {
        applicationContextRunner.run(context -> assertThat(context.getBean(VerificationCodeGenerator.class))
                .isExactlyInstanceOf(VerificationCodeGenerator.class));
    }

    @Test
    void doesNotEnableDisabledMailService() {
        applicationContextRunner.withPropertyValues("spring.profiles.active=dev", "platform.verification.mail.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(VerificationCodeGenerator.class);
                    assertThat(context).doesNotHaveBean(MailVerificationService.class);
                });
    }
}
