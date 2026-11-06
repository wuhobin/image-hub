package com.aurora.imagehub;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.hutool.crypto.digest.BCrypt;
import com.aurora.imagehub.mapper.UserMapper;
import com.aurora.imagehub.ratelimit.AttemptLimiter;
import com.aurora.imagehub.service.UserAccountService;
import com.aurora.starter.verification.mail.MailVerificationSendRequest;
import com.aurora.starter.verification.mail.MailVerificationService;
import com.aurora.starter.verification.mail.MailVerificationVerifyRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 实际 HTTP 与数据库验证找回、改密、验证码用途和用户/管理员会话隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:infrastructure-test.properties", properties = {
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure",
        "spring.datasource.url=jdbc:h2:mem:image_hub_password;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORECASE=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=file:deploy/db/schema.sql",
        "platform.security.is-log=false"
})
@Import({PasswordFlowTest.MemorySessions.class, SettingsCacheTestConfiguration.class})
class PasswordFlowTest {

    @TestConfiguration
    static class MemorySessions {

        @Bean
        SaTokenDao saTokenDao() {
            return new SaTokenDaoDefaultImpl();
        }
    }

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SaTokenDao saTokenDao;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    @MockitoSpyBean
    private UserMapper userMapper;

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private LettuceConnectionFactory lettuceConnectionFactory;

    @MockitoBean
    private AttemptLimiter attemptLimiter;

    @MockitoBean
    private MailVerificationService mailVerificationService;

    private final Map<String, String> codes = new ConcurrentHashMap<>();

    private static final String PASSWORD = "original-password";

    private static final String NEW_PASSWORD = "updated-password";

    private static final String CODE = "654321";

    @BeforeEach
    void setup() {
        SaManager.setSaTokenDao(saTokenDao);
        jdbcTemplate.update("DELETE FROM hub_image");
        jdbcTemplate.update("DELETE FROM hub_user");
        jdbcTemplate.update("DELETE FROM hub_admin");
        String hash = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4));
        jdbcTemplate.update("INSERT INTO hub_user(id, username, email, password_hash) VALUES(1,'owner','owner@example.test',?)", hash);
        jdbcTemplate.update("INSERT INTO hub_user(id, username, email, password_hash) VALUES(2,'other','other@example.test',?)", hash);
        jdbcTemplate.update("INSERT INTO hub_admin(id, username, password_hash) VALUES(1,'operator',?)", hash);
        codes.clear();
        doAnswer(call -> {
            MailVerificationSendRequest request = call.getArgument(0);
            codes.put(request.scene().code() + ":" + request.email(), CODE);
            return null;
        }).when(mailVerificationService).send(any());
        when(mailVerificationService.verifyAndConsume(any())).thenAnswer(call -> {
            MailVerificationVerifyRequest request = call.getArgument(0);
            return codes.remove(request.scene().code() + ":" + request.email(), request.code());
        });
    }

    @Test
    void resetMailUsesIndependentSceneUniformResponseAndBothRateLimits() {
        JsonNode known = post("/api/app/auth/password-reset/email-code", Map.of("email", "OWNER@example.test"), null);
        JsonNode unknown = post("/api/app/auth/password-reset/email-code", Map.of("email", "absent@example.test"), null);
        // traceId 每次请求独立生成；业务响应保持一致，不能据此判断邮箱是否存在。
        assertThat(known.path("code")).isEqualTo(unknown.path("code"));
        assertThat(known.path("message")).isEqualTo(unknown.path("message"));
        assertThat(known.path("data")).isEqualTo(unknown.path("data"));
        assertThat(known.path("code").asInt()).isEqualTo(200);
        verify(mailVerificationService, times(1)).send(argThat(request ->
                request.email().equals("owner@example.test") && request.scene().code().equals("IMAGE_HUB_PASSWORD_RESET")));
        verify(attemptLimiter).check("password-reset-email", "owner@example.test", 1, 60);
        verify(attemptLimiter).check("password-reset-email", "absent@example.test", 1, 60);
        verify(attemptLimiter, times(2)).check(eq("password-reset-email-ip"), anyString(), eq(5), eq(300));
        assertThat(post("/api/app/auth/password-reset/email-code", Map.of("email", "invalid"), null).path("code").asInt()).isEqualTo(400);
    }

    @Test
    void resetRejectsRegistrationExpiredAndReusedCodesThenRevokesOnlyOwnersSessions() {
        String first = login("owner", PASSWORD);
        String second = login("owner", PASSWORD);
        String other = login("other", PASSWORD);
        String admin = post("/api/admin/auth/login", Map.of("username", "operator", "password", PASSWORD), null)
                .path("data").path("token").asText();
        codes.put("IMAGE_HUB_REGISTER:owner@example.test", CODE);
        assertThat(reset("owner@example.test", CODE).path("code").asInt()).isEqualTo(400);
        assertThat(codes).containsKey("IMAGE_HUB_REGISTER:owner@example.test");
        assertThat(reset("absent@example.test", CODE).path("code").asInt()).isEqualTo(400);
        post("/api/app/auth/password-reset/email-code", Map.of("email", "owner@example.test"), null);
        assertThat(reset("owner@example.test", "000000").path("code").asInt()).isEqualTo(400);
        assertThat(reset("OWNER@example.test", CODE).path("code").asInt()).isEqualTo(200);
        assertThat(reset("owner@example.test", CODE).path("code").asInt()).isEqualTo(400);
        assertThat(get("/api/app/auth/me", first).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/app/auth/me", second).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/app/auth/me", other).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/admin/auth/me", admin).path("code").asInt()).isEqualTo(200);
        assertThat(post("/api/app/auth/login", Map.of("username", "owner", "password", PASSWORD), null).path("code").asInt()).isEqualTo(400);
        assertThat(login("owner", NEW_PASSWORD)).isNotBlank();
        assertThat(BCrypt.checkpw(NEW_PASSWORD, userMapper.findById(1).getPasswordHash())).isTrue();
    }

    @Test
    void changePasswordRequiresCurrentPasswordAndRejectsInvalidOrUnchangedInput() {
        String first = login("owner", PASSWORD);
        String second = login("owner", PASSWORD);
        Map<String, String> input = Map.of("currentPassword", PASSWORD, "password", NEW_PASSWORD);
        assertThat(post("/api/app/auth/password", input, null).path("code").asInt()).isEqualTo(401);
        assertThat(post("/api/app/auth/password", Map.of("currentPassword", "wrong-password", "password", NEW_PASSWORD), first)
                .path("code").asInt()).isEqualTo(400);
        assertThat(post("/api/app/auth/password", Map.of("currentPassword", PASSWORD, "password", PASSWORD), first)
                .path("code").asInt()).isEqualTo(400);
        assertThat(post("/api/app/auth/password", Map.of("currentPassword", PASSWORD, "password", "密".repeat(25)), first)
                .path("code").asInt()).isEqualTo(400);
        assertThat(post("/api/app/auth/password", Map.of("currentPassword", PASSWORD, "password", "123"), first)
                .path("code").asInt()).isEqualTo(400);
        assertThat(get("/api/app/auth/me", first).path("code").asInt()).isEqualTo(200);
        assertThat(post("/api/app/auth/password", input, first).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/app/auth/me", first).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/app/auth/me", second).path("code").asInt()).isEqualTo(401);
        assertThat(login("owner", NEW_PASSWORD)).isNotBlank();
    }

    @Test
    void deletedAccountsAndRateLimitedRequestsCannotResetPassword() {
        jdbcTemplate.update("UPDATE hub_user SET deleted=1 WHERE id=1");
        assertThat(post("/api/app/auth/password-reset/email-code", Map.of("email", "owner@example.test"), null).path("code").asInt()).isEqualTo(200);
        verify(mailVerificationService, never()).send(any());
        codes.put("IMAGE_HUB_PASSWORD_RESET:owner@example.test", CODE);
        assertThat(reset("owner@example.test", CODE).path("code").asInt()).isEqualTo(400);
        doThrow(new com.aurora.starter.webmvc.exception.BizException(429, "操作过于频繁，请稍后再试"))
                .when(attemptLimiter).check("password-reset", "other@example.test", 10, 300);
        codes.put("IMAGE_HUB_PASSWORD_RESET:other@example.test", CODE);
        assertThat(reset("other@example.test", CODE).path("code").asInt()).isEqualTo(429);
        assertThat(codes).containsKey("IMAGE_HUB_PASSWORD_RESET:other@example.test");
    }

    @Test
    void loginWaitingBehindResetCannotIssueSessionUsingOldPassword() throws Exception {
        var entered = new CountDownLatch(1);
        doAnswer(call -> {
            var user = userMapper.findById(1);
            entered.countDown();
            return user;
        }).when(userMapper).findByUsername("owner");
        codes.put("IMAGE_HUB_PASSWORD_RESET:owner@example.test", CODE);
        try (var executorService = Executors.newSingleThreadExecutor()) {
            var result = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<JsonNode>>();
            new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
                userMapper.lockById(1);
                result.set(executorService.submit(() -> post("/api/app/auth/login",
                        Map.of("username", "owner", "password", PASSWORD), null)));
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                userAccountService.resetPassword("owner@example.test", CODE, NEW_PASSWORD);
            });
            assertThat(result.get().get(10, TimeUnit.SECONDS).path("code").asInt()).isEqualTo(400);
        }
    }

    /**
     * 请求包装保持与公开 HTTP 契约一致，不记录敏感请求内容。
     */
    private JsonNode reset(String email, String code) {
        return post("/api/app/auth/password-reset", Map.of("email", email, "code", code, "password", NEW_PASSWORD), null);
    }

    /**
     * 读取登录结果以验证会话，测试输出中不打印 Token。
     */
    private String login(String username, String password) {
        JsonNode response = post("/api/app/auth/login", Map.of("username", username, "password", password), null);
        assertThat(response.path("code").asInt()).isEqualTo(200);
        return response.path("data").path("token").asText();
    }

    private JsonNode post(String path, Map<String, String> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return testRestTemplate.postForObject(path, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return testRestTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
    }
}
