package com.aurora.imagehub;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import com.aurora.starter.oss.template.OssTemplate;
import com.aurora.starter.redis.core.RedisCache;
import com.aurora.starter.verification.mail.MailVerificationService;
import com.aurora.starter.xlock.service.LockService;
import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.platform.QiniuKodoFileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.util.ClassUtils;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "platform.verification.mail.enabled=true",
        "platform.verification.mail.from=noreply@example.test",
        "spring.mail.host=smtp.example.test",
        "spring.mail.username=noreply@example.test",
        "spring.mail.password=test-mail-password"
})
@Import(PlatformModulesTest.ProtectedEndpointConfiguration.class)
class PlatformModulesTest extends InfrastructureTestSupport {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Test
    void shouldAssemblePlatformModulesWithoutQuartz() {
        assertThat(applicationContext.getBean(RedisCache.class)).isNotNull();
        assertThat(applicationContext.getBean(LockService.class)).isNotNull();
        assertThat(applicationContext.getBean(SaTokenDao.class)).isInstanceOf(SaTokenDaoForRedisson.class);
        assertThat(applicationContext.getBean(OssTemplate.class)).isNotNull();
        FileStorageService storageService = applicationContext.getBean(FileStorageService.class);
        assertThat(storageService.getFileStorageList()).hasSize(1);
        QiniuKodoFileStorage storage = (QiniuKodoFileStorage) storageService.getFileStorage();
        assertThat(storage.getPlatform()).isEqualTo("qiniu-kodo-1");
        assertThat(storage.getBucketName()).isEqualTo("image-hub-test");
        assertThat(storage.getDomain()).isEqualTo("https://cdn.example.test/");
        assertThat(storage.getBasePath()).isEqualTo("images/");
        assertThat(applicationContext.containsBean("imageVerificationService")).isFalse();
        assertThat(applicationContext.containsBean("smsVerificationService")).isFalse();
        assertThat(applicationContext.containsBean("aliyunSmsVerificationClient")).isFalse();
        assertThat(applicationContext.getBean(MailVerificationService.class)).isNotNull();
        assertThat(ClassUtils.isPresent("cloud.tianai.captcha.application.ImageCaptchaApplication", getClass().getClassLoader())).isFalse();
        JavaMailSenderImpl mailSender = applicationContext.getBean(JavaMailSenderImpl.class);
        assertThat(mailSender.getHost()).isEqualTo("smtp.example.test");
        assertThat(mailSender.getUsername()).isEqualTo("noreply@example.test");
        assertThat(ClassUtils.isPresent("jakarta.mail.Session", getClass().getClassLoader())).isTrue();
        assertThat(ClassUtils.isPresent("com.aliyun.dypnsapi20170525.Client", getClass().getClassLoader())).isFalse();
        assertThat(ClassUtils.isPresent("org.quartz.Scheduler", getClass().getClassLoader())).isFalse();
    }

    @Test
    void shouldRejectAnonymousAccessToBusinessEndpoints() {
        JsonNode response = testRestTemplate.getForObject("/api/test/protected", JsonNode.class);

        assertThat(response).isNotNull();
        assertThat(response.path("code").asInt()).isEqualTo(401);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtectedEndpointConfiguration {

        @Bean
        ProtectedController protectedController() {
            return new ProtectedController();
        }
    }

    @RestController
    static class ProtectedController {

        @GetMapping("/api/test/protected")
        String protectedEndpoint() {
            return "authenticated";
        }
    }
}
