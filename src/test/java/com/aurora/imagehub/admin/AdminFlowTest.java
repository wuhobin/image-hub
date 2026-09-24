package com.aurora.imagehub.admin;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.*;
import cn.hutool.crypto.digest.BCrypt;
import com.aurora.imagehub.config.aigenerate.AiEndpointPolicy;
import com.aurora.imagehub.config.aigenerate.ModelKeyCipher;
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
@Import({AdminFlowTest.TestBeans.class, com.aurora.imagehub.SettingsCacheTestConfiguration.class})
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
    @Autowired private com.aurora.imagehub.service.admin.SystemSettingsService systemSettingsService;
    @Autowired private com.aurora.starter.redis.core.TwoLevelCache twoLevelCache;
    @MockitoBean private RedissonClient redissonClient;
    @MockitoBean private LettuceConnectionFactory lettuceConnectionFactory;
    @MockitoBean private AttemptLimiter attemptLimiter;
    private final Map<String, String> quotaState = new ConcurrentHashMap<>();
    private static final String PASSWORD = "test-password-123";
    private static final String HASH = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4));

    @BeforeEach
    void setup() {
        SaManager.setSaTokenDao(saTokenDao);
        com.aurora.imagehub.SettingsCacheTestConfiguration.resetCache(twoLevelCache);
        jdbcTemplate.update("UPDATE hub_settings SET config_value='100', deleted=0 WHERE config_key='upload.free-total'");
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
        quotaState.put("image-hub:quota:{1}", "u:13");
        quotaState.put("image-hub:quota:{2}", "u:100:in-flight");
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

    /**
     * 签到奖励仅管理员可改，校验整数边界，并复用配置缓存的提交后刷新。
     */
    @Test
    void checkInSettingsValidateAndRefreshAtomically() {
        jdbcTemplate.update("UPDATE hub_settings SET config_value='10' WHERE config_key='check-in.daily-points'");
        jdbcTemplate.update("UPDATE hub_settings SET config_value='30' WHERE config_key='check-in.bonus-points'");
        String path = "/api/admin/settings/check-in";
        user(1, "ordinary", "ordinary@example.test", 0);
        String ordinary = login("/api/app/auth/login", "ordinary");
        assertThat(get(path, null).path("code").asInt()).isEqualTo(401);
        assertThat(request(path, HttpMethod.PUT, Map.of("dailyPoints", 20, "bonusPoints", 50), ordinary).path("code").asInt()).isEqualTo(401);
        String token = login("/api/admin/auth/login", "operator");
        assertThat(get(path, token).path("data").path("dailyPoints").asInt()).isEqualTo(10);
        for (Object invalid : List.of(0, -1, 1.5, 2147483648L, "invalid")) {
            assertThat(request(path, HttpMethod.PUT, Map.of("dailyPoints", invalid, "bonusPoints", 30), token).path("code").asInt()).isEqualTo(400);
            assertThat(request(path, HttpMethod.PUT, Map.of("dailyPoints", 10, "bonusPoints", invalid), token).path("code").asInt()).isEqualTo(400);
        }
        assertThat(request(path, HttpMethod.PUT, Map.of("dailyPoints", 10), token).path("code").asInt()).isEqualTo(400);
        doThrow(new org.redisson.RedissonShutdownException("offline")).when(twoLevelCache).evict(anyString());
        assertThat(request(path, HttpMethod.PUT, Map.of("dailyPoints", 20, "bonusPoints", 50), token).path("code").asInt()).isEqualTo(503);
        assertThat(get(path, token).path("data").path("dailyPoints").asInt()).isEqualTo(10);
        com.aurora.imagehub.SettingsCacheTestConfiguration.resetCache(twoLevelCache);
        assertThat(request(path, HttpMethod.PUT, Map.of("dailyPoints", 20, "bonusPoints", 50), token).path("code").asInt()).isEqualTo(200);
        assertThat(systemSettingsService.checkInRewards().getDailyPoints()).isEqualTo(20);
        assertThat(systemSettingsService.checkInRewards().getBonusPoints()).isEqualTo(50);
        assertThat(systemSettingsService.freeUploadQuota()).isEqualTo(100);
        verify(twoLevelCache).set("image-hub:settings:check-in.daily-points", "20", 3L, java.util.concurrent.TimeUnit.DAYS);
        verify(twoLevelCache).set("image-hub:settings:check-in.bonus-points", "50", 3L, java.util.concurrent.TimeUnit.DAYS);
    }

    @Test
    void settingsRequireAdminValidateNumbersAndRefreshOnlyAfterCommit() {
        user(1, "ordinary", "ordinary@example.test", 0);
        String ordinary = login("/api/app/auth/login", "ordinary");
        assertThat(get("/api/admin/settings", null).path("code").asInt()).isEqualTo(401);
        assertThat(request("/api/admin/settings", HttpMethod.PUT, Map.of("freeUploadQuota", 0), ordinary).path("code").asInt()).isEqualTo(401);
        String token = login("/api/admin/auth/login", "operator");
        assertThat(get("/api/admin/settings", token).path("data").path("freeUploadQuota").asInt()).isEqualTo(100);
        for (Object input : List.of(-1, 1.5, 2147483648L, "invalid")) {
            assertThat(request("/api/admin/settings", HttpMethod.PUT, Map.of("freeUploadQuota", input), token).path("code").asInt()).isEqualTo(400);
        }
        assertThat(request("/api/admin/settings", HttpMethod.PUT, Map.of(), token).path("code").asInt()).isEqualTo(400);
        var observed = new ArrayList<Integer>();
        doAnswer(call -> {
            observed.add(jdbcTemplate.queryForObject("SELECT config_value FROM hub_settings WHERE config_key='upload.free-total'", Integer.class));
            return null;
        }).when(twoLevelCache).evict(anyString());
        var response = request("/api/admin/settings", HttpMethod.PUT, Map.of("freeUploadQuota", 200), token);
        assertThat(response.path("code").asInt()).isEqualTo(200);
        assertThat(response.path("data").path("freeUploadQuota").asInt()).isEqualTo(200);
        assertThat(response.path("data").path("updateTime").asText()).isNotBlank();
        assertThat(observed).containsExactly(100, 200);
        var order = inOrder(twoLevelCache);
        order.verify(twoLevelCache, times(2)).evict("image-hub:settings:upload.free-total");
        order.verify(twoLevelCache).set("image-hub:settings:upload.free-total", "200", 3L, java.util.concurrent.TimeUnit.DAYS);
        jdbcTemplate.update("UPDATE hub_admin SET deleted=1 WHERE id=1");
        assertThat(request("/api/admin/settings", HttpMethod.PUT, Map.of("freeUploadQuota", 0), token).path("code").asInt()).isEqualTo(401);
    }

    @Test
    void settingsUseIndependentKeysAndRejectInvalidQuotaValues() {
        String token = login("/api/admin/auth/login", "operator");
        jdbcTemplate.update("UPDATE hub_settings SET id=9001 WHERE config_key='upload.free-total'");
        jdbcTemplate.update("INSERT INTO hub_settings(config_key,config_value,description) VALUES('test.site-title','测试站点','仅用于验证扩展存储')");
        try {
            assertThat(systemSettingsService.getValue("test.site-title")).isEqualTo("测试站点");
            assertThat(systemSettingsService.freeUploadQuota()).isEqualTo(100);
            verify(twoLevelCache).get(eq("image-hub:settings:test.site-title"), any(java.util.function.Supplier.class), eq(3L), eq(java.util.concurrent.TimeUnit.DAYS));
            verify(twoLevelCache).get(eq("image-hub:settings:upload.free-total"), any(java.util.function.Supplier.class), eq(3L), eq(java.util.concurrent.TimeUnit.DAYS));
            assertThat(request("/api/admin/settings", HttpMethod.PUT, Map.of("freeUploadQuota", 250), token).path("code").asInt()).isEqualTo(200);
            assertThat(systemSettingsService.freeUploadQuota()).isEqualTo(250);
            assertThat(jdbcTemplate.queryForObject("SELECT config_value FROM hub_settings WHERE config_key='test.site-title'", String.class)).isEqualTo("测试站点");
            verify(twoLevelCache, never()).evict("image-hub:settings:test.site-title");
            for (String invalid : List.of("text", "-1", "1.5", "2147483648")) {
                jdbcTemplate.update("UPDATE hub_settings SET config_value=? WHERE config_key='upload.free-total'", invalid);
                assertThat(get("/api/admin/settings", token).path("code").asInt()).isEqualTo(503);
                org.assertj.core.api.Assertions.assertThatThrownBy(systemSettingsService::freeUploadQuota)
                        .isInstanceOf(com.aurora.starter.webmvc.exception.BizException.class);
            }
        } finally {
            jdbcTemplate.update("DELETE FROM hub_settings WHERE config_key='test.site-title'");
            jdbcTemplate.update("UPDATE hub_settings SET id=1,config_value='100' WHERE config_key='upload.free-total'");
        }
    }

    @Test
    void settingsCacheFailuresFollowDatabaseCommitBoundary() {
        String token = login("/api/admin/auth/login", "operator");
        doThrow(new org.redisson.RedissonShutdownException("offline")).when(twoLevelCache).evict(anyString());
        assertThat(request("/api/admin/settings", HttpMethod.PUT, Map.of("freeUploadQuota", 0), token).path("code").asInt()).isEqualTo(503);
        assertThat(get("/api/admin/settings", token).path("data").path("freeUploadQuota").asInt()).isEqualTo(100);
        verify(twoLevelCache, never()).set(anyString(), any(), anyLong(), any());

        com.aurora.imagehub.SettingsCacheTestConfiguration.resetCache(twoLevelCache);
        doThrow(new org.redisson.RedissonShutdownException("offline after commit"))
                .when(twoLevelCache).set(anyString(), any(), anyLong(), any());
        assertThat(request("/api/admin/settings", HttpMethod.PUT, Map.of("freeUploadQuota", 0), token).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/admin/settings", token).path("data").path("freeUploadQuota").asInt()).isZero();

        user(1, "alice", "one@example.test", 0);
        quotaState.put("image-hub:quota:{1}", "u:80");
        when(twoLevelCache.get(anyString(), any(java.util.function.Supplier.class), anyLong(), any()))
                .thenThrow(new org.redisson.RedissonShutdownException("read offline"));
        assertThat(get("/api/admin/users", token).path("data").path("records").get(0).path("remaining").asInt()).isZero();
        jdbcTemplate.update("UPDATE hub_settings SET deleted=1 WHERE config_key='upload.free-total'");
        assertThat(get("/api/admin/settings", token).path("code").asInt()).isEqualTo(503);
        assertThat(get("/api/admin/users", token).path("code").asInt()).isEqualTo(503);
    }

    @Test
    void settingsChangesRecalculateConsumptionAndIgnoreInvalidRedisStatesWithoutWritingBalances() {
        String token = login("/api/admin/auth/login", "operator");
        user(1, "unsupported", "unsupported@example.test", 0);
        user(2, "current", "current@example.test", 0);
        user(3, "invalid", "invalid@example.test", 0);
        quotaState.put("image-hub:quota:{1}", "20");
        quotaState.put("image-hub:quota:{2}", "u:80");
        quotaState.put("image-hub:quota:{3}", "u:-1");
        var original = new HashMap<>(quotaState);
        for (int total : new int[]{50, 0, 200, 100}) {
            assertThat(request("/api/admin/settings", HttpMethod.PUT, Map.of("freeUploadQuota", total), token).path("code").asInt()).isEqualTo(200);
            var records = get("/api/admin/users", token).path("data").path("records");
            assertThat(records.get(0).path("remaining").isNull()).isTrue();
            assertThat(records.get(1).path("remaining").asInt()).isEqualTo(Math.max(0, total - 80));
            assertThat(records.get(2).path("remaining").isNull()).isTrue();
        }
        assertThat(quotaState).isEqualTo(original);
        verify(redissonClient, never()).getLock(anyString());
    }


    @Autowired
    private ModelKeyCipher modelKeyCipher;

    /**
     * 通过真实管理接口验证 Gemini 全部档位、五位数长边及模型间边界隔离。
     */
    @Test
    void geminiSizesAllowOfficialPresetsWithoutRelaxingOtherModels() {
        String admin = login("/api/admin/auth/login", "operator");
        var body = new HashMap<String, Object>();
        body.put("name", "Gemini size validation");
        body.put("modelCode", "gemini-3.1-flash-image");
        body.put("baseUrl", "https://api.openai.com");
        body.put("qualities", List.of("medium"));
        body.put("defaultQuality", "medium");
        var sizes = List.of(
                "512x512", "1024x1024", "2048x2048", "4096x4096",
                "256x1024", "512x2048", "1024x4096", "2048x8192",
                "192x1536", "384x3072", "768x6144", "1536x12288",
                "424x632", "848x1264", "1696x2528", "3392x5056",
                "632x424", "1264x848", "2528x1696", "5056x3392",
                "448x600", "896x1200", "1792x2400", "3584x4800",
                "1024x256", "2048x512", "4096x1024", "8192x2048",
                "600x448", "1200x896", "2400x1792", "4800x3584",
                "464x576", "928x1152", "1856x2304", "3712x4608",
                "576x464", "1152x928", "2304x1856", "4608x3712",
                "1536x192", "3072x384", "6144x768", "12288x1536",
                "384x688", "768x1376", "1536x2752", "3072x5504",
                "688x384", "1376x768", "2752x1536", "5504x3072",
                "792x168", "1584x672", "3168x1344", "6336x2688");
        body.put("sizes", sizes);
        body.put("defaultSize", "1536x12288");
        try {
            var result = post("/api/admin/ai-models", body, admin);
            assertThat(result.path("code").asInt()).isEqualTo(200);
            assertThat(result.path("data").path("sizes").size()).isEqualTo(56);
            assertThat(result.path("data").path("defaultSize").asText()).isEqualTo("1536x12288");
            String path = "/api/admin/ai-models/" + result.path("data").path("id").asText();
            for (String code : List.of("gemini-3.1-flash-image-preview", "gpt-image-2", "gpt-image-2-preview", "another-model")) {
                body.put("modelCode", code);
                assertThat(request(path, HttpMethod.PUT, body, admin).path("code").asInt())
                        .isEqualTo(code.startsWith("gemini-") ? 200 : 400);
            }
            body.put("modelCode", "gemini-3.1-flash-image");
            for (String invalid : List.of("167x1024", "12289x1536", "4096x8192", "12288x512", "100000x192")) {
                body.put("sizes", List.of("1024x1024", invalid));
                body.put("defaultSize", "1024x1024");
                assertThat(request(path, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(400);
            }
            body.put("sizes", Collections.nCopies(65, "1024x1024"));
            assertThat(request(path, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(400);
        } finally {
            jdbcTemplate.update("DELETE FROM hub_ai_model WHERE name='Gemini size validation'");
        }
    }

    @MockitoBean
    private AiEndpointPolicy aiEndpointPolicy;

    @Test
    void modelConfigurationProtectsKeysAndValidatesDefaultsAndAdminAccess() {
        String cacheKey = "image-hub:models:available";
        var cachedModels = new ConcurrentHashMap<String, Object>();
        var loads = new java.util.concurrent.atomic.AtomicInteger();
        // 使用父项目同款序列化器回读，验证 Redis 命中后仍为 VO 列表而非 Map。
        var serializer = new org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer();
        when(twoLevelCache.get(eq(cacheKey), any(java.util.function.Supplier.class), eq(3L), eq(java.util.concurrent.TimeUnit.DAYS)))
                .thenAnswer(call -> cachedModels.computeIfAbsent(cacheKey, key -> {
                    loads.incrementAndGet();
                    Object value = call.getArgument(1, java.util.function.Supplier.class).get();
                    return serializer.deserialize(serializer.serialize(value));
                }));
        doAnswer(call -> {
            cachedModels.remove(cacheKey);
            return null;
        }).when(twoLevelCache).evict(cacheKey);
        user(1, "model-user", "model-user@example.test", 0);
        String ordinary = login("/api/app/auth/login", "model-user");
        String admin = login("/api/admin/auth/login", "operator");
        assertThat(get("/api/app/generations/models", ordinary).path("data").size()).isZero();
        assertThat(get("/api/app/generations/models", ordinary).path("data").size()).isZero();
        assertThat(loads.get()).isEqualTo(1);
        var body = new HashMap<String, Object>();
        body.put("name", "Test model");
        body.put("modelCode", "gpt-image-2");
        body.put("baseUrl", "https://api.openai.com");
        body.put("imagesPath", "/v1/images/generations");
        body.put("apiKey", "test-only-admin-key");
        var sizes = List.of("1024x1024", "2048x2048", "2880x2880", "1536x1024", "2160x1440", "3456x2304", "1024x1536", "1440x2160", "2304x3456", "1280x720", "2560x1440", "3840x2160", "720x1280", "1440x2560", "2160x3840", "1024x768", "2048x1536", "3200x2400", "768x1024", "1536x2048", "2400x3200", "1344x576", "2016x864", "3808x1632");
        body.put("sizes", sizes);
        body.put("defaultSize", "1024x1024");
        body.put("qualities", List.of("low", "medium", "high"));
        body.put("defaultQuality", "medium");
        body.put("enabled", true);
        body.put("sortOrder", 10);
        assertThat(post("/api/admin/ai-models", body, ordinary).path("code").asInt()).isEqualTo(401);
        var result = post("/api/admin/ai-models", body, admin);
        assertThat(result.path("code").asInt()).isEqualTo(200);
        String id = result.path("data").path("id").asText();
        assertThat(result.path("data").path("pointsCost").asInt()).isEqualTo(1);
        assertThat(result.path("data").path("keyConfigured").asBoolean()).isTrue();
        assertThat(result.toString()).doesNotContain("test-only-admin-key", "apiKeyCiphertext");
        String cipher = jdbcTemplate.queryForObject("SELECT api_key_ciphertext FROM hub_ai_model WHERE id=?", String.class, id);
        assertThat(cipher).doesNotContain("test-only-admin-key");
        assertThat(modelKeyCipher.decrypt(cipher)).isEqualTo("test-only-admin-key");
        var available = get("/api/app/generations/models", ordinary);
        assertThat(available.path("data").size()).isEqualTo(1);
        assertThat(available.path("data").get(0).path("sizes").size()).isEqualTo(sizes.size());
        for (int i = 0; i < sizes.size(); i++) {
            assertThat(available.path("data").get(0).path("sizes").get(i).asText()).isEqualTo(sizes.get(i));
        }
        assertThat(available.toString()).doesNotContain("baseUrl", "imagesPath", "apiKey", "test-only");
        assertThat(get("/api/app/generations/models", ordinary).path("data")).isEqualTo(available.path("data"));
        assertThat(loads.get()).isEqualTo(2);
        assertThat(get("/api/admin/ai-models", admin).path("data").path("records").size()).isEqualTo(2);

        body.put("apiKey", "");
        for (Object invalid : java.util.Arrays.asList(0, -1, 1.5, 2147483648L, null)) {
            body.put("pointsCost", invalid);
            assertThat(request("/api/admin/ai-models/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(400);
        }
        body.put("pointsCost", 6);
        doThrow(new org.redisson.RedissonShutdownException("offline")).when(twoLevelCache).evict(cacheKey);
        assertThat(request("/api/admin/ai-models/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(503);
        assertThat(request("/api/admin/ai-models/" + id, HttpMethod.DELETE, null, admin).path("code").asInt()).isEqualTo(503);
        assertThat(jdbcTemplate.queryForObject("SELECT points_cost FROM hub_ai_model WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_ai_model WHERE id=?", Integer.class, id)).isZero();
        doAnswer(call -> {
            cachedModels.remove(cacheKey);
            return null;
        }).when(twoLevelCache).evict(cacheKey);
        assertThat(request("/api/admin/ai-models/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT points_cost FROM hub_ai_model WHERE id=?", Integer.class, id)).isEqualTo(6);
        assertThat(get("/api/app/generations/models", ordinary).path("data").get(0).path("pointsCost").asInt()).isEqualTo(6);
        assertThat(loads.get()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT api_key_ciphertext FROM hub_ai_model WHERE id=?", String.class, id)).isEqualTo(cipher);
        body.put("defaultSize", "512x512");
        assertThat(request("/api/admin/ai-models/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(400);
        body.put("defaultSize", "1024x1024");
        for (String invalid : List.of("1537x1024", "2048x512", "512x512", "3840x3840", "4096x1024")) {
            body.put("sizes", List.of("1024x1024", invalid));
            assertThat(request("/api/admin/ai-models/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(400);
        }
        body.put("sizes", sizes);
        body.put("qualities", List.of("unsupported"));
        assertThat(request("/api/admin/ai-models/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(400);
        body.put("qualities", List.of("low", "medium", "high"));
        body.put("enabled", false);
        assertThat(request("/api/admin/ai-models/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/app/generations/models", ordinary).path("data").size()).isZero();
        assertThat(loads.get()).isEqualTo(4);
        body.put("enabled", true);
        assertThat(request("/api/admin/ai-models/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/app/generations/models", ordinary).path("data").size()).isEqualTo(1);
        assertThat(request("/api/admin/ai-models/" + id, HttpMethod.DELETE, null, admin).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/app/generations/models", ordinary).path("data").size()).isZero();
        assertThat(loads.get()).isEqualTo(6);
        when(twoLevelCache.get(eq(cacheKey), any(java.util.function.Supplier.class), anyLong(), any()))
                .thenThrow(new org.redisson.RedissonShutdownException("read offline"));
        var fallback = get("/api/app/generations/models", ordinary);
        assertThat(fallback.path("code").asInt()).isEqualTo(200);
        assertThat(fallback.path("data").size()).isZero();
        assertThat(post("/api/admin/ai-models", body, admin).path("code").asInt()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_ai_model WHERE id=?", Integer.class, id)).isEqualTo(1);
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
