package com.aurora.imagehub.admin;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.*;
import cn.hutool.crypto.digest.BCrypt;
import com.aurora.imagehub.ratelimit.AttemptLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际 HTTP 与数据库验证双账号隔离、搜索分页、Redis 直读和未知路由默认拒绝。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:infrastructure-test.properties", properties = {
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure",
        "spring.datasource.url=jdbc:h2:mem:image_hub_admin;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORECASE=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=file:deploy/db/schema.sql",
        "platform.security.is-log=false"
})
@Import(AdminFlowTest.TestBeans.class)
class AdminFlowTest {
    @TestConfiguration
    static class TestBeans {
        @Bean SaTokenDao saTokenDao() { return new SaTokenDaoDefaultImpl(); }
        @Bean UnmappedController unmappedController() { return new UnmappedController(); }
    }
    @RestController
    static class UnmappedController {
        @GetMapping("/api/unmapped") String read() { return "must not be public"; }
        @GetMapping("/api/app/test/protected") Map<String, Integer> app() { return Map.of("code", 200); }
    }
    @Autowired private TestRestTemplate testRestTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SaTokenDao saTokenDao;
    @MockitoBean private RedissonClient redissonClient;
    @MockitoBean private LettuceConnectionFactory lettuceConnectionFactory;
    @MockitoBean private AttemptLimiter attemptLimiter;
    private final Map<String, String> quotaState = new ConcurrentHashMap<>();
    private static final String PASSWORD = "test-password-123";
    private static final String HASH = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4));

    @BeforeEach
    void setup() {
        SaManager.setSaTokenDao(saTokenDao);
        jdbcTemplate.update("DELETE FROM hub_image");
        jdbcTemplate.update("DELETE FROM hub_user");
        jdbcTemplate.update("DELETE FROM hub_admin");
        jdbcTemplate.update("INSERT INTO hub_admin(id,username,password_hash) VALUES(1,'operator',?)", HASH);
        quotaState.clear();
        RBuckets rBuckets = mock(RBuckets.class);
        when(redissonClient.getBuckets(StringCodec.INSTANCE)).thenReturn(rBuckets);
        when(rBuckets.get(any(String[].class))).thenAnswer(call -> {
            var result = new HashMap<String, String>();
            for (Object arg : call.getArguments()) {
                for (String key : arg instanceof String[] array ? array : new String[]{(String) arg}) {
                    if (quotaState.containsKey(key)) result.put(key, quotaState.get(key));
                }
            }
            return result;
        });
    }

    @Test
    void accountsTokensAndLogoutAreIsolatedAndRemovedAdminsLoseAccess() {
        user(1, "operator", "ordinary@example.test", 0);
        String userToken = login("/api/app/auth/login", "operator");
        assertThat(get("/api/app/test/protected", null).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/app/test/protected", userToken).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/admin/users", null).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/admin/users", userToken).path("code").asInt()).isEqualTo(401);
        user(2, "ordinary", "ordinary2@example.test", 0);
        assertThat(post("/api/admin/auth/login", Map.of("username", "ordinary", "password", PASSWORD), null)
                .path("code").asInt()).isEqualTo(400);
        String first = login("/api/admin/auth/login", "operator");
        String second = login("/api/admin/auth/login", "operator");
        assertThat(first).isNotEqualTo(second).isNotEqualTo(userToken);
        assertThat(get("/api/app/images", first).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/app/test/protected", first).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/app/auth/me", first).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/admin/auth/me", first).path("data").path("username").asText()).isEqualTo("operator");
        assertThat(get("/api/admin/users", first).path("code").asInt()).isEqualTo(200);
        assertThat(post("/api/admin/auth/logout", Map.of(), first).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/admin/users", first).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/admin/users", second).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/app/auth/me", userToken).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/unmapped", null).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/unmapped", userToken).path("code").asInt()).isEqualTo(403);
        assertThat(get("/api/unmapped", second).path("code").asInt()).isEqualTo(403);
        assertThat(get("/api/nonexistent", null).path("code").asInt()).isEqualTo(404);
        String profileToken = login("/api/admin/auth/login", "operator");
        String logoutToken = login("/api/admin/auth/login", "operator");
        jdbcTemplate.update("UPDATE hub_admin SET deleted=1 WHERE id=1");
        assertThat(get("/api/admin/users", second).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/admin/auth/me", profileToken).path("code").asInt()).isEqualTo(401);
        assertThat(post("/api/admin/auth/logout", Map.of(), logoutToken).path("code").asInt()).isEqualTo(401);
        assertThat(post("/api/admin/auth/login", Map.of("username", "operator", "password", PASSWORD), null)
                .path("code").asInt()).isEqualTo(400);
    }

    @Test
    void searchPagesAndReadsOnlyRedisBalancesWithoutRecovery() {
        user(1, "alice", "one@example.test", 0);
        user(2, "bob", "ALICE@example.test", 0);
        user(3, "carol", "three@example.test", 0);
        user(4, "archived-alice", "deleted@example.test", 1);
        quotaState.put("image-hub:quota:v1:{1}", "87");
        quotaState.put("image-hub:quota:v1:{2}", "0:in-flight");
        String token = login("/api/admin/auth/login", "operator");
        var original = new HashMap<>(quotaState);
        JsonNode result = get("/api/admin/users?search=ALICE&pageSize=1&page=1", token);
        assertThat(result.path("code").asInt()).isEqualTo(200);
        JsonNode data = result.path("data");
        assertThat(data.path("total").asInt()).isEqualTo(2);
        assertThat(data.path("records").size()).isEqualTo(1);
        assertThat(data.path("records").get(0).path("id").asText()).isEqualTo("2");
        assertThat(data.path("records").get(0).path("remaining").asInt()).isZero();
        JsonNode next = get("/api/admin/users?search=alice&pageSize=1&page=2", token).path("data").path("records").get(0);
        assertThat(next.path("remaining").asInt()).isEqualTo(87);
        assertThat(next.has("passwordHash")).isFalse();
        assertThat(next.has("total")).isFalse();
        JsonNode missing = get("/api/admin/users?search=carol", token).path("data").path("records").get(0);
        assertThat(missing.path("remaining").isNull()).isTrue();
        assertThat(quotaState).isEqualTo(original);
        verify(redissonClient, never()).getLock(anyString());
        verify(redissonClient, never()).getBucket(anyString(), eq(StringCodec.INSTANCE));
        for (String query : List.of("page=0", "pageSize=101", "search=" + "x".repeat(255))) {
            assertThat(get("/api/admin/users?" + query, token).path("code").asInt()).isEqualTo(400);
        }
        assertThat(get("/api/admin/users?search=%25", token).path("data").path("total").asInt()).isZero();
        assertThat(get("/api/admin/users?page=99", token).path("data").path("records").size()).isZero();
        assertThat(post("/api/admin/auth/login", Map.of("username", "operator", "password", "bad"), null)
                .path("code").asInt()).isEqualTo(400);
        assertThat(post("/api/admin/users", Map.of(), token).path("code").asInt()).isNotEqualTo(200);
        verify(attemptLimiter, atLeastOnce()).check(eq("admin-login-ip"), anyString(), eq(30), eq(300));
        when(redissonClient.getBuckets(StringCodec.INSTANCE)).thenThrow(new IllegalStateException("offline"));
        assertThat(get("/api/admin/users", token).path("code").asInt()).isEqualTo(503);
    }

    private void user(long id, String username, String email, int deleted) {
        jdbcTemplate.update("INSERT INTO hub_user(id,username,email,password_hash,deleted,create_time) VALUES(?,?,?,?,?,?)",
                id, username, email, HASH, deleted, java.sql.Timestamp.valueOf("2026-09-14 10:00:00"));
    }
    private String login(String path, String username) {
        JsonNode response = post(path, Map.of("username", username, "password", PASSWORD), null);
        assertThat(response.path("code").asInt()).isEqualTo(200);
        return response.path("data").path("token").asText();
    }
    private JsonNode get(String path, String token) { return request(path, HttpMethod.GET, null, token); }
    private JsonNode post(String path, Object body, String token) { return request(path, HttpMethod.POST, body, token); }
    private JsonNode request(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return testRestTemplate.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
    }
}
