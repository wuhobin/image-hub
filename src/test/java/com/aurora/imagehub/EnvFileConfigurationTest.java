package com.aurora.imagehub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnvFileConfigurationTest {

    @TempDir
    Path directory;

    @Test
    void importsEnvValuesAndSelectsItsProfile() throws IOException {
        Path envFile = writeEnv();
        runner(envFile).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("19090");
            assertThat(context.getEnvironment().getProperty("spring.mail.username"))
                    .isEqualTo("test@example.test");
            assertThat(context.getEnvironment().getProperty("platform.verification.mail.enabled"))
                    .isEqualTo("true");
            assertThat(context.getEnvironment().getActiveProfiles()).contains("dev");
        });
    }

    @Test
    void explicitProductionProfileOverridesEnvDevelopmentProfile() throws IOException {
        runner(writeEnv()).withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getActiveProfiles())
                            .contains("prod").doesNotContain("dev");
                    assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled"))
                            .isEqualTo("false");
                });
    }

    @Test
    void missingOptionalEnvStillAllowsExternalConfiguration() {
        runner(directory.resolve("missing.env"))
                .withPropertyValues("SERVER_PORT=19091", "spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("19091");
                });
    }


    /**
     * 生产环境只信任回环或显式指定的代理，防止任意客户端转发头影响注册 IP。
     */
    @Test
    void productionProxyTrustIsExplicit() throws IOException {
        runner(writeEnv()).withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isEqualTo("native");
            var trusted = java.util.regex.Pattern.compile(context.getEnvironment().getRequiredProperty("server.tomcat.remoteip.internal-proxies"));
            assertThat(trusted.matcher("127.0.0.1").matches()).isTrue();
            assertThat(trusted.matcher("::1").matches()).isTrue();
            assertThat(trusted.matcher("127x0x0x1").matches()).isFalse();
            assertThat(trusted.matcher("198.51.100.8").matches()).isFalse();
            assertThat(trusted.matcher("172.19.0.1").matches()).isFalse();
        });
        runner(writeEnv()).withPropertyValues("spring.profiles.active=prod", "TRUSTED_PROXY_REGEX=172[.]19[.]0[.]1").run(context -> {
            var trusted = java.util.regex.Pattern.compile(context.getEnvironment().getRequiredProperty("server.tomcat.remoteip.internal-proxies"));
            assertThat(trusted.matcher("172.19.0.1").matches()).isTrue();
            assertThat(trusted.matcher("172.19.0.2").matches()).isFalse();
        });
    }

    private Path writeEnv() throws IOException {
        return Files.writeString(directory.resolve("test.env"), """
                SERVER_PORT=19090
                SPRING_PROFILES_ACTIVE=dev
                MAIL_USERNAME=test@example.test
                MAIL_PASSWORD=fake-password
                MAIL_VERIFICATION_ENABLED=true
                """);
    }

    private ApplicationContextRunner runner(Path envFile) {
        // 只加载配置数据，不装配业务应用或连接外部服务。
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("ENV_FILE=" + envFile.toAbsolutePath().toString().replace('\\', '/'));
    }
}
