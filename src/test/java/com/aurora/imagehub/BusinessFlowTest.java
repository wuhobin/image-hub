package com.aurora.imagehub;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.hutool.crypto.digest.BCrypt;
import com.aurora.imagehub.config.aigenerate.AiImageClient;
import com.aurora.imagehub.config.aigenerate.ModelKeyCipher;
import com.aurora.imagehub.mapper.UserMapper;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.service.UserAccountService;
import com.aurora.imagehub.service.ImageFileService;
import com.aurora.imagehub.ratelimit.AttemptLimiter;
import com.aurora.starter.oss.template.OssTemplate;
import com.aurora.starter.verification.mail.MailVerificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.upload.UploadPretreatment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:infrastructure-test.properties", properties = {
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure",
        "spring.datasource.url=jdbc:h2:mem:image_hub_business;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORECASE=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=file:deploy/db/schema.sql,classpath:h2-datetime-precision.sql",
        "platform.security.is-log=false", "image-hub.ai.max-image-size=20MB"
})
@Import({BusinessFlowTest.MemorySessions.class, SettingsCacheTestConfiguration.class})
class BusinessFlowTest {
    @TestConfiguration
    static class MemorySessions {
        @Bean SaTokenDao saTokenDao() { return new SaTokenDaoDefaultImpl(); }
    }

    @Autowired TestRestTemplate testRestTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserMapper userMapper;
    @Autowired UserAccountService userAccountService;
    @Autowired ImageFileService imageFileService;

    @Autowired com.aurora.imagehub.cache.UploadQuotaCache uploadQuotaCache;
    @Autowired ObjectMapper objectMapper;
    @Autowired SaTokenDao saTokenDao;
    @Autowired com.aurora.starter.redis.core.TwoLevelCache twoLevelCache;
    @MockitoBean RedissonClient redissonClient;
    @MockitoBean LettuceConnectionFactory lettuceConnectionFactory;
    @MockitoBean AttemptLimiter attemptLimiter;
    @MockitoBean MailVerificationService mailVerificationService;
    @MockitoBean OssTemplate ossTemplate;
    @MockitoSpyBean com.aurora.imagehub.mapper.ImageMapper imageMapper;

    @MockitoSpyBean com.aurora.imagehub.mapper.QuotaUsageMapper quotaUsageMapper;

    @MockitoBean
    java.time.Clock clock;

    private final Map<String, String> codes = new ConcurrentHashMap<>();
    private FileStorageService storage;
    private FileInfo stored;
    private final Map<String, String> quotaState = new ConcurrentHashMap<>();

    private final Map<String, byte[]> referenceState = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        SaManager.setSaTokenDao(saTokenDao);
        SettingsCacheTestConfiguration.resetCache(twoLevelCache);
        jdbcTemplate.update("UPDATE hub_settings SET config_value='100', deleted=0 WHERE config_key='upload.free-total'");
        jdbcTemplate.update("DELETE FROM hub_quota_usage");
        jdbcTemplate.update("DELETE FROM hub_image");
        jdbcTemplate.update("DELETE FROM hub_ai_generation");
        jdbcTemplate.update("UPDATE hub_ai_model SET points_cost=1");
        jdbcTemplate.update("DELETE FROM hub_user");
        jdbcTemplate.update("DELETE FROM hub_user_check_in");
        jdbcTemplate.update("UPDATE hub_settings SET config_value='10', deleted=0 WHERE config_key='check-in.daily-points'");
        jdbcTemplate.update("UPDATE hub_settings SET config_value='30', deleted=0 WHERE config_key='check-in.bonus-points'");
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-09-21T04:00:00Z"));
        codes.clear();
        quotaState.clear();
        referenceState.clear();
        var locks = new ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>();
        when(redissonClient.getBucket(anyString(), eq(org.redisson.client.codec.StringCodec.INSTANCE))).thenAnswer(call -> {
            String key = call.getArgument(0);
            org.redisson.api.RBucket<String> bucket = mock(org.redisson.api.RBucket.class);
            when(bucket.get()).thenAnswer(ignored -> quotaState.get(key));
            when(bucket.setIfAbsent(anyString())).thenAnswer(write -> quotaState.putIfAbsent(key, write.getArgument(0)) == null);
            doAnswer(write -> { quotaState.put(key, write.getArgument(0)); return null; }).when(bucket).set(anyString());
            return bucket;
        });
        when(redissonClient.getBucket(anyString(), eq(org.redisson.client.codec.ByteArrayCodec.INSTANCE))).thenAnswer(call -> {
            String key = call.getArgument(0);
            org.redisson.api.RBucket<byte[]> bucket = mock(org.redisson.api.RBucket.class);
            when(bucket.get()).thenAnswer(ignored -> referenceState.get(key));
            doAnswer(write -> {
                assertThat(write.<java.time.Duration>getArgument(1)).isEqualTo(java.time.Duration.ofMinutes(10));
                referenceState.put(key, write.getArgument(0));
                return null;
            }).when(bucket).set(any(byte[].class), any(java.time.Duration.class));
            when(bucket.delete()).thenAnswer(ignored -> referenceState.remove(key) != null);
            return bucket;
        });
        when(redissonClient.getLock(anyString())).thenAnswer(call -> {
            var mutex = locks.computeIfAbsent(call.getArgument(0), ignored -> new java.util.concurrent.locks.ReentrantLock());
            org.redisson.api.RLock lock = mock(org.redisson.api.RLock.class);
            when(lock.tryLock()).thenAnswer(ignored -> mutex.tryLock());
            when(lock.tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenAnswer(ignored ->
                    mutex.tryLock(ignored.getArgument(0), ignored.getArgument(1)));
            when(lock.isHeldByCurrentThread()).thenAnswer(ignored -> mutex.isHeldByCurrentThread());
            doAnswer(ignored -> { mutex.unlock(); return null; }).when(lock).unlock();
            return lock;
        });
        doAnswer(call -> {
            var request = call.getArgument(0, com.aurora.starter.verification.mail.MailVerificationSendRequest.class);
            codes.put(request.email(), "654321");
            return null;
        }).when(mailVerificationService).send(any());
        when(mailVerificationService.verifyAndConsume(any())).thenAnswer(call -> {
            var request = call.getArgument(0, com.aurora.starter.verification.mail.MailVerificationVerifyRequest.class);
            return codes.remove(request.email(), request.code());
        });
        storage = mock(FileStorageService.class);
        when(ossTemplate.getFileStorageService()).thenReturn(storage);
        var qiniuKodoFileStorage = new org.dromara.x.file.storage.core.platform.QiniuKodoFileStorage();
        qiniuKodoFileStorage.setPlatform("qiniu-kodo-1");
        qiniuKodoFileStorage.setBasePath("base/");
        qiniuKodoFileStorage.setDomain("https://cdn.example.test/");
        when(storage.getFileStorage()).thenReturn(qiniuKodoFileStorage);
        stored = new FileInfo().setPlatform("qiniu-kodo-1").setBasePath("base/").setPath("images/test/")
                .setFilename("stored.png").setUrl("https://cdn.example.test/base/images/test/stored.png")
                .setSize(100L).setContentType("image/png")
                .setCreateTime(java.util.Date.from(java.time.Instant.parse("2026-09-14T01:57:41.489Z")));
        when(storage.of(any(MultipartFile.class))).thenAnswer(call -> {
            UploadPretreatment upload = mock(UploadPretreatment.class, Answers.RETURNS_SELF);
            when(upload.upload()).thenReturn(stored);
            var filename = new java.util.concurrent.atomic.AtomicReference<String>();
            var path = new java.util.concurrent.atomic.AtomicReference<String>();
            when(upload.setSaveFilename(anyString())).thenAnswer(set -> { filename.set(set.getArgument(0)); return upload; });
            when(upload.setPath(anyString())).thenAnswer(set -> { path.set(set.getArgument(0)); return upload; });
            when(upload.upload(eq(qiniuKodoFileStorage), any(), any())).thenAnswer(save -> {
                FileInfo generated = new FileInfo().setPlatform("qiniu-kodo-1").setBasePath("base/")
                        .setPath(path.get()).setFilename(filename.get())
                        .setUrl("https://cdn.example.test/base/" + path.get() + filename.get());
                // 网络上传开始前必须已落库，而且定位必须与实际对象一致。
                assertThat(jdbcTemplate.queryForList("SELECT pending_storage_info FROM hub_ai_generation WHERE pending_storage_info IS NOT NULL", String.class))
                        .anySatisfy(info -> assertThat(info).contains("\"url\":\"" + generated.getUrl() + "\""));
                return generated;
            });
            return upload;
        });
        when(storage.exists(any(FileInfo.class))).thenReturn(true);
        when(ossTemplate.delete(any(FileInfo.class))).thenReturn(true);
    }


    /**
     * 按北京时间跨日，第 7、14 天各发一次额外奖励，漏签后重新累计。
     */
    @Test
    void checkInUsesShanghaiDaysAndRepeatingSevenDayRewards() {
        assertThat(get("/api/app/check-in", null).path("code").asInt()).isEqualTo(401);
        assertThat(post("/api/app/check-in", Map.of(), null).path("code").asInt()).isEqualTo(401);
        register("checkin", "checkin@example.test");
        String token = login("checkin");
        var start = java.time.Instant.parse("2026-09-20T15:59:59Z");
        when(clock.instant()).thenReturn(start);
        assertThat(get("/api/app/check-in", token).path("data").path("signedIn").asBoolean()).isFalse();
        for (int day = 1; day <= 14; day++) {
            when(clock.instant()).thenReturn(day == 1 ? start : start.plusSeconds(1 + (day - 2) * 86400L));
            JsonNode result = post("/api/app/check-in", Map.of("userId", -1, "dailyPoints", 9999), token);
            assertThat(result.path("code").asInt()).isEqualTo(200);
            assertThat(result.path("data").path("consecutiveDays").asInt()).isEqualTo(day);
            assertThat(result.path("data").path("rewardPoints").asInt()).isEqualTo(day % 7 == 0 ? 40 : 10);
            assertThat(post("/api/app/check-in", Map.of(), token).path("data")).isEqualTo(result.path("data"));
            if (day == 2) assertThat(result.path("data").path("date").asText()).isEqualTo("2026-09-21");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_user_check_in", Integer.class)).isEqualTo(14);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_quota_usage WHERE scene='CHECK_IN_BONUS'", Integer.class)).isEqualTo(2);
        assertThat(get("/api/app/images/quota", token).path("data").path("total").asLong()).isEqualTo(300);
        assertThat(get("/api/app/quota/records?page=1&pageSize=20", token).path("data").path("total").asInt()).isEqualTo(16);
        when(clock.instant()).thenReturn(start.plusSeconds(16 * 86400L));
        assertThat(get("/api/app/check-in", token).path("data").path("consecutiveDays").asInt()).isZero();
        assertThat(post("/api/app/check-in", Map.of(), token).path("data").path("consecutiveDays").asInt()).isEqualTo(1);
        register("checkin-other", "checkin-other@example.test");
        String other = login("checkin-other");
        assertThat(get("/api/app/check-in", other).path("data").path("signedIn").asBoolean()).isFalse();
        assertThat(get("/api/app/quota/records", other).path("data").path("total").asInt()).isZero();
    }

    /**
     * 并发签到只发一次；收入立即可消费，Redis 丢失不会丢掉收入。
     */
    @Test
    void concurrentCheckInRewardsAreSpendableAndSurviveCacheLoss() throws Exception {
        register("checkin-race", "checkin-race@example.test");
        String token = login("checkin-race");
        long userId = userMapper.findByUsername("checkin-race").getId();
        jdbcTemplate.update("UPDATE hub_settings SET config_value='0' WHERE config_key='upload.free-total'");
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return post("/api/app/check-in", Map.of(), token);
            });
            var second = executor.submit(() -> {
                start.await();
                return post("/api/app/check-in", Map.of(), token);
            });
            start.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).path("code").asInt()).isEqualTo(200);
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS).path("code").asInt()).isEqualTo(200);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_user_check_in", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_quota_usage", Integer.class)).isEqualTo(1);
        assertThat(upload(token, "earned.png", png()).path("code").asInt()).isEqualTo(200);
        long modelId = enableAiModel();
        jdbcTemplate.update("UPDATE hub_ai_model SET points_cost=6 WHERE id=?", modelId);
        String id = post("/api/app/generations", generationRequest(modelId), token).path("data").path("id").asText();
        when(aiImageClient.generate(any())).thenReturn(png());
        aiGenerationService.runTask(userId, id);
        assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("SUCCEEDED");
        quotaState.clear();
        assertThat(imageFileService.quota(userId).getRemaining()).isEqualTo(3);
        assertThat(imageFileService.quota(userId).getTotal()).isEqualTo(10);
    }

    /**
     * 第七天收入写入失败必须整体回滚，重试时使用当前配置并保留已领取快照。
     */
    @Test
    void checkInRollsBackBothRewardsAndSnapshotsAmounts() {
        register("checkin-rollback", "checkin-rollback@example.test");
        String token = login("checkin-rollback");
        long userId = userMapper.findByUsername("checkin-rollback").getId();
        jdbcTemplate.update("INSERT INTO hub_user_check_in(user_id,check_in_date,consecutive_days,daily_points,bonus_points) VALUES(?, '2026-09-20', 6, 10, 0)", userId);
        doThrow(new IllegalStateException("simulated bonus failure")).when(quotaUsageMapper).insert(
                argThat((com.aurora.imagehub.model.entity.QuotaUsage usage) -> usage != null && "CHECK_IN_BONUS".equals(usage.getScene())));
        assertThat(post("/api/app/check-in", Map.of(), token).path("code").asInt()).isNotEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_user_check_in", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_quota_usage", Integer.class)).isZero();
        reset(quotaUsageMapper);
        jdbcTemplate.update("UPDATE hub_settings SET config_value='2147483647' WHERE config_key IN ('check-in.daily-points','check-in.bonus-points')");
        assertThat(post("/api/app/check-in", Map.of(), token).path("data").path("rewardPoints").asLong()).isEqualTo(4294967294L);
        jdbcTemplate.update("UPDATE hub_settings SET config_value='20' WHERE config_key='check-in.daily-points'");
        assertThat(post("/api/app/check-in", Map.of(), token).path("data").path("rewardPoints").asLong()).isEqualTo(4294967294L);
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asLong()).isEqualTo(4294967394L);
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-09-22T04:00:00Z"));
        assertThat(post("/api/app/check-in", Map.of(), token).path("data").path("rewardPoints").asInt()).isEqualTo(20);
    }

    @Test
    void responseDatesUseShanghaiTimeWithSecondPrecision() {
        var image = new com.aurora.imagehub.model.vo.ImageVO();
        image.setCreatedAt(java.util.Date.from(java.time.Instant.parse("2026-09-13T17:57:42.123Z")));
        assertThat(objectMapper.valueToTree(image).path("createdAt").asText()).isEqualTo("2026-09-14 01:57:42");
        assertThat(objectMapper.valueToTree(Map.of("time", image.getCreatedAt())).path("time").asText())
                .isEqualTo("2026-09-14 01:57:42");
    }

    @Test
    void registrationSessionsAndOwnershipWorkEndToEnd() throws Exception {
        assertThat(get("/api/app/images", null).path("code").asInt()).isEqualTo(401);
        String username = "alice";
        register(username, "Alice@example.test");
        assertThat(get("/api/app/auth/me", null).path("code").asInt()).isEqualTo(401);
        assertThat(userMapper.findByUsername(username).getPasswordHash()).startsWith("$2");
        assertThat(BCrypt.checkpw("secret123", userMapper.findByUsername(username).getPasswordHash())).isTrue();
        assertThat(post("/api/app/auth/login", Map.of("username", username, "password", "wrong123"), null).path("code").asInt()).isEqualTo(400);
        // 校验继承的通用查询正确映射 hub_user，而非按类名推导表名。
        assertThat(userAccountService.getById(userMapper.findByUsername(username).getId()).getUsername()).isEqualTo(username);
        String first = login(username);
        String second = login(username);
        assertThat(first).isNotEqualTo(second);
        assertThat(get("/api/app/auth/me", first).path("data").path("username").asText()).isEqualTo(username);

        JsonNode upload = upload(first, "photo.png", png());
        assertThat(upload.path("code").asInt()).isEqualTo(200);
        JsonNode image = upload.path("data");
        assertThat(image.path("width").asInt()).isEqualTo(3);
        assertThat(image.path("height").asInt()).isEqualTo(2);
        assertThat(image.has("storageInfo")).isFalse();
        assertThat(image.path("url").asText()).isEqualTo(stored.getUrl());
        assertThat(image.path("preview").asText()).isEqualTo(stored.getUrl()
                + "?imageView2/2/w/600/h/600/q/75/format/webp/ignore-error/1");
        ImageFile persisted = imageFileService.getById(image.path("id").asText());
        assertThat(persisted.getUserId()).isEqualTo(userMapper.findByUsername(username).getId());
        assertThat(persisted.getStorageInfo()).isNotBlank();
        assertThat(java.time.OffsetDateTime.parse(objectMapper.readTree(persisted.getStorageInfo())
                .path("createTime").asText()).toInstant()).isEqualTo(stored.getCreateTime().toInstant());
        assertThat(persisted.getDeleted()).isZero();
        assertThat(persisted.getCreateTime()).isNotNull();
        assertThat(persisted.getUpdateTime()).isNotNull();
        assertThat(persisted.getCreateTime().toInstant().getNano()).isZero();
        assertThat(persisted.getUpdateTime().toInstant().getNano()).isZero();
        assertThat(image.path("createdAt").asText()).isEqualTo(java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(persisted.getCreateTime().toInstant()));
        var createTime = persisted.getCreateTime();
        // 制造旧的数据库时间，确认更新时间来自数据库，而非传入实体。
        jdbcTemplate.update("UPDATE hub_image SET update_time = ? WHERE id = ?",
                java.sql.Timestamp.valueOf("2001-01-01 00:00:00"), persisted.getId());
        persisted.setName("photo-renamed.png");
        persisted.setCreateTime(new java.util.Date(0));
        persisted.setUpdateTime(new java.util.Date(0));
        assertThat(imageFileService.updateById(persisted)).isTrue();
        ImageFile updated = imageFileService.getById(persisted.getId());
        assertThat(updated.getCreateTime()).isEqualTo(createTime);
        assertThat(updated.getUpdateTime()).isAfter(java.sql.Timestamp.valueOf("2001-01-01 00:00:00"));
        assertThat(updated.getUpdateTime().toInstant().getNano()).isZero();
        JsonNode page = get("/api/app/images?search=photo&type=PNG&page=1&pageSize=1", first).path("data").path("page");
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(page.path("current").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(1);
        assertThat(page.path("pages").asInt()).isEqualTo(1);
        JsonNode stats = get("/api/app/images", first).path("data");
        assertThat(stats.path("page").path("total").asInt()).isEqualTo(1);
        assertThat(stats.path("totalBytes").asInt()).isEqualTo(png().length);
        assertThat(page.path("records").get(0).path("id")).isEqualTo(image.path("id"));
        assertThat(page.path("records").get(0).path("createdAt")).isEqualTo(image.path("createdAt"));
        assertThat(page.path("records").get(0).path("url")).isEqualTo(image.path("url"));
        assertThat(page.path("records").get(0).path("preview")).isEqualTo(image.path("preview"));
        assertThat(get("/api/app/images?search=absent", first).path("data").path("page").path("total").asInt()).isZero();
        assertThat(get("/api/app/images?pageSize=101", first).path("code").asInt()).isEqualTo(400);

        register("bob", "bob@example.test");
        String bob = login("bob");
        assertThat(get("/api/app/images", null).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/app/images", bob).path("data").path("page").path("total").asInt()).isZero();
        assertThat(get("/api/app/images", bob).path("data").path("totalBytes").asInt()).isZero();
        assertThat(get("/api/app/images", bob).path("data").path("page").path("total").asInt()).isZero();
        assertThat(delete(image.path("id").asText(), bob).path("code").asInt()).isEqualTo(404);
        verify(ossTemplate, never()).delete(any(FileInfo.class));

        assertThat(post("/api/app/auth/logout", Map.of(), first).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/app/auth/me", first).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/app/auth/me", second).path("code").asInt()).isEqualTo(200);
        assertThat(delete(image.path("id").asText(), second).path("code").asInt()).isEqualTo(200);
        var key = org.mockito.ArgumentCaptor.forClass(FileInfo.class);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_image WHERE id = ?", Integer.class, persisted.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT update_time FROM hub_image WHERE id = ?", java.sql.Timestamp.class, persisted.getId()))
                .isAfterOrEqualTo(new java.sql.Timestamp(updated.getUpdateTime().getTime()));
        assertThat(imageFileService.getById(persisted.getId())).isNull();
        assertThat(imageMapper.findOwned(persisted.getUserId(), persisted.getId())).isNull();
        assertThat(delete(persisted.getId(), second).path("code").asInt()).isEqualTo(404);
        verify(ossTemplate).delete(key.capture());
        assertThat(key.getValue().getBasePath()).isEqualTo("base/");
        assertThat(key.getValue().getPath()).isEqualTo("images/test/");
        assertThat(key.getValue().getFilename()).isEqualTo("stored.png");
        assertThat(get("/api/app/images", second).path("data").path("page").path("total").asInt()).isZero();
        assertThat(get("/api/app/images", second).path("data").path("totalBytes").asInt()).isZero();
    }

    @Test
    void uploadTimeSortingAppliesBeforePaginationAndKeepsUserFilters() throws Exception {
        register("sorting", "sorting@example.test");
        String token = login("sorting");
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 4; i++) ids.add(upload(token, "sort.png", png()).path("data").path("id").asText());
        ids.sort(String::compareTo);
        // 中间两张同秒上传，分页必须按主键稳定排序；两端验证时间优先级。
        for (int i = 0; i < ids.size(); i++) jdbcTemplate.update("UPDATE hub_image SET create_time = ? WHERE id = ?",
                java.sql.Timestamp.valueOf("2026-09-14 12:00:0" + (i == 3 ? 0 : i == 0 ? 2 : 1)), ids.get(i));
        String deleted = upload(token, "sort-deleted.png", png()).path("data").path("id").asText();
        assertThat(delete(deleted, token).path("code").asInt()).isEqualTo(200);
        register("sort-other", "sort-other@example.test");
        upload(login("sort-other"), "sort-other.png", png());

        for (String sort : new String[]{"asc", "desc"}) {
            var actual = new java.util.ArrayList<String>();
            for (int page = 1; page <= 2; page++) {
                JsonNode response = get("/api/app/images?search=sort&type=PNG&pageSize=2&page=" + page + "&sort=" + sort, token);
                assertThat(response.path("code").asInt()).isEqualTo(200);
                assertThat(response.path("data").path("page").path("total").asInt()).isEqualTo(4);
                response.path("data").path("page").path("records").forEach(record -> actual.add(record.path("id").asText()));
            }
            var expected = new java.util.ArrayList<>(java.util.List.of(ids.get(3), ids.get(1), ids.get(2), ids.get(0)));
            if (sort.equals("desc")) java.util.Collections.reverse(expected);
            assertThat(actual).containsExactlyElementsOf(expected);
        }
        assertThat(get("/api/app/images?pageSize=1", token).path("data").path("page").path("records").get(0).path("id").asText()).isEqualTo(ids.get(0));
        assertThat(get("/api/app/images?sort=asc&type=JPG", token).path("data").path("page").path("total").asInt()).isZero();
        assertThat(get("/api/app/images?sort=asc&search=missing", token).path("data").path("page").path("total").asInt()).isZero();
        assertThat(get("/api/app/images?sort=invalid", token).path("code").asInt()).isEqualTo(400);
    }

    @Test
    void imageStatsFilterTypesAndExcludeDeletedAndOtherUsers() throws Exception {
        register("stats", "stats@example.test");
        String token = login("stats");
        var sizes = Map.of("PNG", 1024L, "JPG", 2048L, "WEBP", 4096L, "GIF", 8192L);
        for (var entry : sizes.entrySet()) {
            String id = upload(token, "stats-" + entry.getKey() + ".png", png()).path("data").path("id").asText();
            jdbcTemplate.update("UPDATE hub_image SET type = ?, size = ? WHERE id = ?", entry.getKey(), entry.getValue(), id);
        }
        String deleted = upload(token, "deleted.png", png()).path("data").path("id").asText();
        assertThat(delete(deleted, token).path("code").asInt()).isEqualTo(200);
        register("stats-other", "stats-other@example.test");
        String otherToken = login("stats-other");
        upload(otherToken, "other.png", png());

        for (var entry : sizes.entrySet()) {
            JsonNode response = get("/api/app/images?type=" + entry.getKey().toLowerCase(java.util.Locale.ROOT), token);
            assertThat(response.path("code").asInt()).isEqualTo(200);
            assertThat(response.path("data").path("page").path("total").asInt()).isEqualTo(1);
            assertThat(response.path("data").path("totalBytes").asLong()).isEqualTo(entry.getValue());
            JsonNode list = get("/api/app/images?search=stats&type=" + entry.getKey() + "&pageSize=1&page=2", token).path("data");
            assertThat(list.path("page").path("total").asInt()).isEqualTo(1);
            assertThat(list.path("page").path("records").size()).isZero();
            assertThat(list.path("totalBytes").asLong()).isEqualTo(entry.getValue());
        }
        for (String path : new String[]{"/api/app/images", "/api/app/images?type="}) {
            JsonNode stats = get(path, token).path("data");
            assertThat(stats.path("page").path("total").asInt()).isEqualTo(4);
            assertThat(stats.path("totalBytes").asLong()).isEqualTo(15360);
        }
        JsonNode empty = get("/api/app/images?type=GIF", otherToken).path("data");
        assertThat(empty.path("page").path("total").asInt()).isZero();
        assertThat(empty.path("totalBytes").asLong()).isZero();
        assertThat(get("/api/app/images?type=invalid", token).path("code").asInt()).isEqualTo(400);
        assertThat(get("/api/app/images?type=PNG", null).path("code").asInt()).isEqualTo(401);
        JsonNode combined = get("/api/app/images?search=stats-PNG&type=png", token).path("data");
        assertThat(combined.path("page").path("total").asInt()).isEqualTo(1);
        assertThat(combined.path("page").path("records").get(0).path("name").asText()).isEqualTo("stats-PNG.png");
        assertThat(combined.path("totalBytes").asLong()).isEqualTo(1024);
        JsonNode noMatch = get("/api/app/images?search=stats-PNG&type=JPG", token).path("data");
        assertThat(noMatch.path("page").path("total").asInt()).isZero();
        assertThat(noMatch.path("totalBytes").asLong()).isZero();
        for (int page = 1; page <= 2; page++) {
            JsonNode list = get("/api/app/images?pageSize=2&page=" + page, token).path("data");
            assertThat(list.path("page").path("total").asInt()).isEqualTo(4);
            assertThat(list.path("page").path("records").size()).isEqualTo(2);
            assertThat(list.path("totalBytes").asLong()).isEqualTo(15360);
        }
    }

    @Test
    void invalidCodesDuplicatesAndFakeImagesAreRejected() throws Exception {
        // 普通 Param 类通过 setter 绑定 JSON，字段上的 Bean Validation 约束仍须生效。
        assertThat(post("/api/app/auth/email-code", Map.of("email", "invalid"), null).path("code").asInt()).isEqualTo(400);
        assertThat(post("/api/app/auth/register", Map.of("username", "bad name", "email", "carol@example.test",
                "password", "secret123", "code", "654321"), null).path("code").asInt()).isEqualTo(400);
        assertThat(post("/api/app/auth/login", Map.of("username", "carol", "password", "123"), null)
                .path("code").asInt()).isEqualTo(400);
        verify(mailVerificationService, never()).send(any());
        verify(mailVerificationService, never()).verifyAndConsume(any());
        var input = Map.of("username", "carol", "email", "carol@example.test", "password", "secret123", "code", "654321");
        assertThat(post("/api/app/auth/register", input, null).path("code").asInt()).isEqualTo(400);
        post("/api/app/auth/email-code", Map.of("email", "carol@example.test"), null);
        assertThat(post("/api/app/auth/register", input, null).path("code").asInt()).isEqualTo(200);
        assertThat(post("/api/app/auth/register", input, null).path("code").asInt()).isEqualTo(409);
        assertThat(post("/api/app/auth/register", Map.of("username", "other", "email", "CAROL@example.test",
                "password", "secret123", "code", "654321"), null).path("code").asInt()).isEqualTo(409);
        String token = login("carol");
        assertThat(upload(token, "fake.png", "<html>not an image</html>".getBytes()).path("code").asInt()).isEqualTo(400);
        assertThat(upload(token, "fake.jpg", png()).path("code").asInt()).isEqualTo(400);
        verify(storage, never()).of(any(MultipartFile.class));
    }

    @Test
    void deletionSupportsBothStoredDateFormatsAndRejectsCorruptMetadata() throws Exception {
        register("legacy", "legacy@example.test");
        String token = login("legacy");
        for (String time : new String[]{"2026-09-14T01:57:41.489+00:00", "2026-09-14T01:57:41.489Z", "2026-09-14 09:57:41"}) {
            String id = upload(token, "legacy.png", png()).path("data").path("id").asText();
            var metadata = objectMapper.createObjectNode();
            metadata.setAll((com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.valueToTree(stored));
            metadata.put("createTime", time);
            jdbcTemplate.update("UPDATE hub_image SET storage_info = ? WHERE id = ?", metadata.toString(), id);
            clearInvocations(ossTemplate);
            assertThat(delete(id, token).path("code").asInt()).isEqualTo(200);
            var fileInfoCaptor = org.mockito.ArgumentCaptor.forClass(FileInfo.class);
            verify(ossTemplate).delete(fileInfoCaptor.capture());
            assertThat(fileInfoCaptor.getValue().getCreateTime().toInstant()).isEqualTo(java.time.Instant.parse(
                    time.contains("T") ? "2026-09-14T01:57:41.489Z" : "2026-09-14T01:57:41Z"));
            assertThat(fileInfoCaptor.getValue().getFilename()).isEqualTo(stored.getFilename());
            assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_image WHERE id = ?", Integer.class, id)).isEqualTo(1);
        }
        String id = upload(token, "corrupt.png", png()).path("data").path("id").asText();
        jdbcTemplate.update("UPDATE hub_image SET storage_info = ? WHERE id = ?", "{\"createTime\":\"invalid-date\"}", id);
        clearInvocations(ossTemplate, storage);
        assertThat(delete(id, token).path("code").asInt()).isEqualTo(500);
        verify(storage, never()).exists(any(FileInfo.class));
        verify(ossTemplate, never()).delete(any(FileInfo.class));
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_image WHERE id = ?", Integer.class, id)).isZero();
    }

    @Test
    void failedCloudDeletionKeepsRecordAndCanBeRetried() throws Exception {
        register("david", "david@example.test");
        String token = login("david");
        String id = upload(token, "retry.png", png()).path("data").path("id").asText();
        when(ossTemplate.delete(any(FileInfo.class))).thenReturn(false);
        assertThat(delete(id, token).path("code").asInt()).isEqualTo(502);
        assertThat(get("/api/app/images", token).path("data").path("page").path("total").asInt()).isEqualTo(1);
        when(storage.exists(any(FileInfo.class))).thenReturn(false);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_image WHERE id = ?", Integer.class, id)).isZero();
        assertThat(delete(id, token).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/app/images", token).path("data").path("page").path("total").asInt()).isZero();
    }

    @Test
    void supportedFormatsAndUploadRollbackAreChecked() throws Exception {
        register("formats", "formats@example.test");
        String token = login("formats");
        assertThat(SaManager.getConfig().getActiveTimeout()).isEqualTo(-1);
        for (String format : new String[]{"jpg", "gif", "png"}) {
            var out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), format, out);
            JsonNode response = upload(token, "photo." + format, out.toByteArray());
            assertThat(response.path("code").asInt()).isEqualTo(200);
            assertThat(response.path("data").path("type").asText()).isEqualTo(format.toUpperCase());
        }
        // A 3 x 2 WebP generated by the browser Canvas encoder.
        byte[] webp = Base64.getDecoder().decode("UklGRkYAAABXRUJQVlA4WAoAAAAQAAAAAgAAAQAAQUxQSAcAAAAAAAAAAAAAAFZQOCAYAAAAMAEAnQEqAwACAAFAJiWkAANwAP79NmgA");
        JsonNode response = upload(token, "photo.webp", webp);
        assertThat(response.path("code").asInt()).isEqualTo(200);
        assertThat(response.path("data").path("width").asInt()).isEqualTo(3);
        assertThat(response.path("data").path("height").asInt()).isEqualTo(2);
        JsonNode firstPage = get("/api/app/images?page=1&pageSize=2", token).path("data").path("page");
        JsonNode secondPage = get("/api/app/images?page=2&pageSize=2", token).path("data").path("page");
        assertThat(firstPage.path("total").asInt()).isEqualTo(4);
        assertThat(firstPage.path("pages").asInt()).isEqualTo(2);
        assertThat(firstPage.path("records").size()).isEqualTo(2);
        assertThat(secondPage.path("current").asInt()).isEqualTo(2);
        assertThat(secondPage.path("records").size()).isEqualTo(2);
        var firstIds = new java.util.HashSet<String>();
        firstPage.path("records").forEach(record -> firstIds.add(record.path("id").asText()));
        secondPage.path("records").forEach(record -> {
            assertThat(firstIds).doesNotContain(record.path("id").asText());
            assertThat(record.has("storageInfo")).isFalse();
        });
        JsonNode beyond = get("/api/app/images?page=3&pageSize=2", token).path("data").path("page");
        assertThat(beyond.path("records").size()).isZero();
        assertThat(beyond.path("total").asInt()).isEqualTo(4);
        assertThat(get("/api/app/images?type=WEBP&pageSize=1", token).path("data").path("page").path("total").asInt()).isEqualTo(1);
        assertThat(get("/api/app/images?search=absent", token).path("data").path("page").path("records").size()).isZero();
        assertThat(get("/api/app/images", token).path("data").path("page").path("total").asInt()).isEqualTo(4);
        assertThat(get("/api/app/images?page=0", token).path("code").asInt()).isEqualTo(400);
        assertThat(upload(token, "too-large.png", new byte[10 * 1024 * 1024 + 1]).path("code").asInt()).isEqualTo(413);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated database failure"))
                .when(imageMapper).insert(any(ImageFile.class));
        assertThat(upload(token, "rollback.png", png()).path("code").asInt()).isEqualTo(500);
        verify(ossTemplate).delete(stored);
        assertThat(get("/api/app/images", token).path("data").path("page").path("total").asInt()).isEqualTo(4);
    }

    @Test
    void accountAuditFieldsAndMybatisLogicDeletionWork() {
        register("archived", "archived@example.test");
        String token = login("archived");
        var user = userMapper.findByUsername("archived");
        assertThat(user.getDeleted()).isZero();
        assertThat(user.getCreateTime()).isNotNull();
        assertThat(user.getUpdateTime()).isNotNull();
        assertThat(user.getCreateTime().toInstant().getNano()).isZero();
        assertThat(user.getUpdateTime().toInstant().getNano()).isZero();
        var createTime = user.getCreateTime();
        jdbcTemplate.update("UPDATE hub_user SET update_time = ? WHERE id = ?",
                java.sql.Timestamp.valueOf("2001-01-01 00:00:00"), user.getId());
        user.setEmail("archived-updated@example.test");
        user.setCreateTime(new java.util.Date(0));
        user.setUpdateTime(new java.util.Date(0));
        assertThat(userAccountService.updateById(user)).isTrue();
        var updated = userAccountService.getById(user.getId());
        assertThat(updated.getCreateTime()).isEqualTo(createTime);
        assertThat(updated.getUpdateTime()).isAfter(java.sql.Timestamp.valueOf("2001-01-01 00:00:00"));
        assertThat(updated.getUpdateTime().toInstant().getNano()).isZero();

        jdbcTemplate.update("UPDATE hub_user SET update_time = ? WHERE id = ?",
                java.sql.Timestamp.valueOf("2001-01-01 00:00:00"), user.getId());
        assertThat(userAccountService.removeById(user.getId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT update_time FROM hub_user WHERE id = ?",
                java.sql.Timestamp.class, user.getId())).isAfter(java.sql.Timestamp.valueOf("2001-01-01 00:00:00"));
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_user WHERE id = ?", Integer.class, user.getId())).isEqualTo(1);
        assertThat(userAccountService.getById(user.getId())).isNull();
        assertThat(userMapper.findById(user.getId())).isNull();
        assertThat(userMapper.findByUsername("archived")).isNull();
        assertThat(post("/api/app/auth/login", Map.of("username", "archived", "password", "secret123"), null).path("code").asInt()).isEqualTo(400);
        assertThat(get("/api/app/auth/me", token).path("code").asInt()).isEqualTo(401);
        assertThat(post("/api/app/auth/register", Map.of("username", "archived", "email", "new@example.test",
                "password", "secret123", "code", "654321"), null).path("code").asInt()).isEqualTo(409);
        assertThat(post("/api/app/auth/email-code", Map.of("email", "archived-updated@example.test"), null).path("code").asInt()).isEqualTo(409);
    }

    @Test
    void databaseIgnoresCallerSuppliedAuditTimesOnInsert() {
        var callerTime = java.sql.Timestamp.valueOf("2001-01-01 00:00:00.123");
        var user = new com.aurora.imagehub.model.entity.UserAccount();
        user.setUsername("database-clock");
        user.setEmail("database-clock@example.test");
        user.setPasswordHash("unused-test-hash");
        user.setCreateTime(callerTime);
        user.setUpdateTime(callerTime);
        assertThat(userAccountService.save(user)).isTrue();
        var savedUser = userAccountService.getById(user.getId());
        assertThat(savedUser.getCreateTime()).isAfter(callerTime);
        assertThat(savedUser.getUpdateTime()).isAfter(callerTime);
        assertThat(savedUser.getCreateTime().toInstant().getNano()).isZero();
        assertThat(savedUser.getUpdateTime().toInstant().getNano()).isZero();

        ImageFile image = new ImageFile();
        image.setId(java.util.UUID.randomUUID().toString());
        image.setUserId(user.getId());
        image.setName("database-clock.png");
        image.setUrl("https://cdn.example.test/database-clock.png");
        image.setType("PNG");
        image.setSize(100);
        image.setWidth(3);
        image.setHeight(2);
        image.setStorageInfo("{}");
        image.setCreateTime(callerTime);
        image.setUpdateTime(callerTime);
        assertThat(imageFileService.save(image)).isTrue();
        ImageFile savedImage = imageFileService.getById(image.getId());
        assertThat(savedImage.getCreateTime()).isAfter(callerTime);
        assertThat(savedImage.getUpdateTime()).isAfter(callerTime);
        assertThat(savedImage.getCreateTime().toInstant().getNano()).isZero();
        assertThat(savedImage.getUpdateTime().toInstant().getNano()).isZero();
    }

    @Test
    void failedUploadReadbackKeepsSavedRecordAndCloudFile() throws Exception {
        register("readback", "readback@example.test");
        String token = login("readback");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated read failure"))
                .when(imageMapper).findOwned(anyLong(), anyString());
        JsonNode response = upload(token, "saved.png", png());
        assertThat(response.path("code").asInt()).isEqualTo(500);
        assertThat(response.path("message").asText()).contains("图片已保存");
        verify(ossTemplate, never()).delete(any(FileInfo.class));
        assertThat(get("/api/app/images", token).path("data").path("page").path("total").asInt()).isEqualTo(1);
    }

    @Test
    void quotaIgnoresLegacyUploadsAndSurvivesDeletionAndRedisLoss() throws Exception {
        register("quota", "quota@example.test");
        String token = login("quota");
        assertThat(get("/api/app/images/quota", null).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(100);
        String legacyId = upload(token, "legacy.png", png()).path("data").path("id").asText();
        jdbcTemplate.update("UPDATE hub_image SET quota_charged = 0 WHERE id = ?", legacyId);
        quotaState.clear();
        referenceState.clear();
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(100);
        // 种入 99 条新版成功记录，其中包含已删除图片；历史记录始终不计费。
        for (int i = 0; i < 99; i++) jdbcTemplate.update("""
                INSERT INTO hub_image (id,user_id,name,url,type,size,width,height,storage_info,quota_charged,deleted)
                SELECT ?,user_id,name,url,type,size,width,height,storage_info,1,1 FROM hub_image WHERE id = ?
                """, java.util.UUID.randomUUID().toString(), legacyId);
        quotaState.clear();
        referenceState.clear();
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(1);
        String lastId = upload(token, "last.png", png()).path("data").path("id").asText();
        assertThat(lastId).isNotBlank();
        assertThat(delete(lastId, token).path("code").asInt()).isEqualTo(200);
        quotaState.clear();
        referenceState.clear();
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isZero();
        clearInvocations(storage);
        assertThat(upload(token, "over.png", png()).path("code").asInt()).isEqualTo(40301);
        verify(storage, never()).of(any(MultipartFile.class));
        register("fresh", "fresh@example.test");
        assertThat(get("/api/app/images/quota", login("fresh")).path("data").path("remaining").asInt()).isEqualTo(100);
    }

    @Test
    void quotaRefundsFailuresAndRecoversInterruptedReservation() throws Exception {
        register("refund", "refund@example.test");
        String token = login("refund");
        long userId = userMapper.findByUsername("refund").getId();
        String key = "image-hub:quota:{" + userId + "}";
        quotaState.put(key, "u:1:interrupted-upload");
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(100);
        UploadPretreatment uploadPretreatment = mock(UploadPretreatment.class, Answers.RETURNS_SELF);
        when(storage.of(any(MultipartFile.class))).thenReturn(uploadPretreatment);
        assertThat(upload(token, "cloud-failed.png", png()).path("code").asInt()).isEqualTo(502);
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(100);
        when(uploadPretreatment.upload()).thenReturn(stored);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated failure"))
                .when(imageMapper).insert(any(ImageFile.class));
        assertThat(upload(token, "database-failed.png", png()).path("code").asInt()).isEqualTo(500);
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(100);
        reset(imageMapper);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("readback failed"))
                .when(imageMapper).findOwned(anyLong(), anyString());
        assertThat(upload(token, "saved.png", png()).path("code").asInt()).isEqualTo(500);
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(99);
        quotaState.put(key, "u:2:another-interrupted-upload");
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(99);
        when(redissonClient.getBucket(anyString(), eq(org.redisson.client.codec.StringCodec.INSTANCE)))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        clearInvocations(storage);
        assertThat(upload(token, "redis-failed.png", png()).path("code").asInt()).isEqualTo(503);
        verify(storage, never()).of(any(MultipartFile.class));
    }

    @Test
    void concurrentUploadCannotSpendTheSameLastQuota() throws Exception {
        register("parallel", "parallel@example.test");
        String token = login("parallel");
        long userId = userMapper.findByUsername("parallel").getId();
        quotaState.put("image-hub:quota:{" + userId + "}", "u:99");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        UploadPretreatment uploadPretreatment = mock(UploadPretreatment.class, Answers.RETURNS_SELF);
        when(storage.of(any(MultipartFile.class))).thenReturn(uploadPretreatment);
        when(uploadPretreatment.upload()).thenAnswer(call -> {
            entered.countDown();
            if (!finish.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return stored;
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        byte[] bytes = png();
        try {
            var first = executor.submit(() -> upload(token, "first.png", bytes));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isZero();
            assertThat(upload(token, "second.png", bytes).path("code").asInt()).isEqualTo(409);
            jdbcTemplate.update("UPDATE hub_settings SET config_value='0' WHERE config_key='upload.free-total'");
            finish.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).path("code").asInt()).isEqualTo(200);
            assertThat(get("/api/app/images/quota", token).path("data").path("used").asLong()).isEqualTo(100);
            jdbcTemplate.update("UPDATE hub_settings SET config_value='100' WHERE config_key='upload.free-total'");
            assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isZero();
            assertThat(upload(token, "third.png", bytes).path("code").asInt()).isEqualTo(40301);
            verify(uploadPretreatment).upload();
        } finally { finish.countDown(); executor.shutdownNow(); }
    }


    @Test
    void changingTotalPreservesConsumptionAcrossZeroAndInvalidOrMissingRedisState() throws Exception {
        register("dynamic", "dynamic@example.test");
        String token = login("dynamic");
        long userId = userMapper.findByUsername("dynamic").getId();
        String first = upload(token, "first.png", png()).path("data").path("id").asText();
        for (int i = 0; i < 79; i++) jdbcTemplate.update("""
                INSERT INTO hub_image (id,user_id,name,url,type,size,width,height,storage_info,quota_charged,deleted)
                SELECT ?,user_id,name,url,type,size,width,height,storage_info,1,1 FROM hub_image WHERE id = ?
                """, java.util.UUID.randomUUID().toString(), first);
        String key = "image-hub:quota:{" + userId + "}";
        quotaState.put(key, "u:80");
        for (int total : new int[]{200, 50, 0, 100}) {
            jdbcTemplate.update("UPDATE hub_settings SET config_value=? WHERE config_key='upload.free-total'", total);
            var quota = get("/api/app/images/quota", token).path("data");
            assertThat(quota.path("total").asInt()).isEqualTo(total);
            assertThat(quota.path("used").asLong()).isEqualTo(80);
            assertThat(quota.path("remaining").asInt()).isEqualTo(Math.max(0, total - 80));
            if (total == 0) {
                clearInvocations(storage);
                assertThat(upload(token, "blocked.png", png()).path("code").asInt()).isEqualTo(40301);
                verify(storage, never()).of(any(MultipartFile.class));
                register("zero-quota", "zero-quota@example.test");
                var fresh = get("/api/app/images/quota", login("zero-quota")).path("data");
                assertThat(fresh.path("used").asLong()).isZero();
                assertThat(fresh.path("remaining").asInt()).isZero();
                assertThat(delete(first, token).path("code").asInt()).isEqualTo(200);
            }
        }
        String next = upload(token, "next.png", png()).path("data").path("id").asText();
        assertThat(quotaState.get(key)).isEqualTo("u:81");
        assertThat(delete(next, token).path("code").asInt()).isEqualTo(200);
        quotaState.clear();
        referenceState.clear();
        var restored = get("/api/app/images/quota", token).path("data");
        assertThat(restored.path("used").asLong()).isEqualTo(81);
        assertThat(restored.path("remaining").asInt()).isEqualTo(19);
        assertThat(quotaState.get(key)).isEqualTo("u:81");
        for (String invalid : List.of("u:invalid", "u:", "u:-1", "u:9223372036854775808", "20", "20:in-flight")) {
            quotaState.put(key, invalid);
            assertThat(get("/api/app/images/quota", token).path("data").path("used").asLong()).isEqualTo(81);
            assertThat(quotaState.get(key)).isEqualTo("u:81");
        }
    }


    @Autowired
    com.aurora.imagehub.service.AiGenerationService aiGenerationService;

    @Autowired
    ModelKeyCipher modelKeyCipher;

    @MockitoBean
    AiImageClient aiImageClient;

    @MockitoSpyBean
    com.aurora.imagehub.mapper.AiGenerationMapper aiGenerationMapper;

    @Test
    void aiReservationSharesQuotaAndSuccessfulImageIsChargedOnce() throws Exception {
        register("creator", "creator@example.test");
        String token = login("creator");
        long userId = userMapper.findByUsername("creator").getId();
        long modelId = enableAiModel();
        jdbcTemplate.update("UPDATE hub_settings SET config_value='2' WHERE config_key='upload.free-total'");
        var request = generationRequest(modelId);
        var submitted = post("/api/app/generations", request, token);
        assertThat(submitted.path("code").asInt()).isEqualTo(200);
        String id = submitted.path("data").path("id").asText();
        assertThat(post("/api/app/generations", request, token).path("data").path("id").asText()).isEqualTo(id);
        assertThat(post("/api/app/generations", generationRequest(modelId), token).path("code").asInt()).isEqualTo(409);
        assertThat(get("/api/app/images/quota", token).path("data").path("reserved").asInt()).isEqualTo(1);
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(1);
        assertThat(upload(token, "normal.png", png()).path("code").asInt()).isEqualTo(200);
        assertThat(upload(token, "over.png", png()).path("code").asInt()).isEqualTo(40301);
        register("other-creator", "other-creator@example.test");
        String other = login("other-creator");
        assertThat(get("/api/app/generations/" + id, other).path("code").asInt()).isEqualTo(404);
        assertThat(post("/api/app/generations/" + id + "/retry-save", Map.of(), other).path("code").asInt()).isEqualTo(404);
        assertThat(post("/api/app/generations/" + id + "/abandon", Map.of(), other).path("code").asInt()).isEqualTo(404);
        assertThat(get("/api/app/generations", other).path("data").path("total").asInt()).isZero();

        // 已接收任务使用快照；管理员之后停用模型不会取消该任务。
        jdbcTemplate.update("UPDATE hub_ai_model SET enabled=0 WHERE id=?", modelId);
        // 模拟长时间排队；耗时应从认领后开始，不使用数据库创建时间。
        jdbcTemplate.update("UPDATE hub_ai_generation SET create_time=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP) WHERE id=?", id);
        assertThat(aiGenerationService.task(userId, id).getDurationSeconds()).isNull();
        when(aiImageClient.generate(any())).thenAnswer(call -> {
            Thread.sleep(50);
            return png();
        });
        long started = System.nanoTime();
        aiGenerationService.runTask(userId, id);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        Long durationMillis = jdbcTemplate.queryForObject("SELECT duration_millis FROM hub_ai_generation WHERE id=?", Long.class, id);
        assertThat(durationMillis).isBetween(50L, elapsedMillis);
        aiGenerationService.runTask(userId, id);
        assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("SUCCEEDED");
        verify(aiImageClient, times(1)).generate(argThat(task -> task.getModelCode().equals("gpt-image-2")));
        var result = get("/api/app/generations/" + id, token);
        assertThat(result.path("data").path("image").path("sourceType").asText()).isEqualTo("AI");
        assertThat(result.path("data").path("durationSeconds").decimalValue())
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(durationMillis, 3));
        assertThat(result.toString()).doesNotContain("apiKey", "baseUrl", "resultData", "workToken");
        assertThat(get("/api/app/images?sourceType=AI", token).path("data").path("page").path("total").asInt()).isEqualTo(1);
        assertThat(get("/api/app/images?sourceType=UPLOAD", token).path("data").path("page").path("total").asInt()).isEqualTo(1);
        assertThat(get("/api/app/images?sourceType=bogus", token).path("code").asInt()).isEqualTo(400);
        assertThat(get("/api/app/images/quota", token).path("data").path("used").asInt()).isEqualTo(2);
        assertThat(get("/api/app/images/quota", token).path("data").path("reserved").asInt()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT api_key_ciphertext FROM hub_ai_generation WHERE id=?", String.class, id)).isNull();
        assertThat(delete(id, token).path("code").asInt()).isEqualTo(200);
        jdbcTemplate.update("UPDATE hub_ai_generation SET update_time=TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP) WHERE id=?", id);
        assertThat(aiGenerationService.task(userId, id).getDurationSeconds())
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(durationMillis, 3));
        quotaState.clear();
        referenceState.clear();
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isZero();
        assertThat(post("/api/app/generations", request, token).path("data").path("id").asText()).isEqualTo(id);
    }

    /** AI 调高上限后直接上传大图片，普通 multipart 上传仍限制为 10 MB。 */
    @Test
    void aiSizeConfigurationAllowsLargeResultsButDoesNotChangeUploadLimit() throws Exception {
        register("large-ai", "large-ai@example.test");
        String token = login("large-ai");
        long userId = userMapper.findByUsername("large-ai").getId();
        long modelId = enableAiModel();
        byte[] large = java.util.Arrays.copyOf(png(), 17 * 1024 * 1024);
        assertThat(upload(token, "large.png", java.util.Arrays.copyOf(large, 10 * 1024 * 1024 + 1)).path("code").asInt()).isEqualTo(413);
        String id = post("/api/app/generations", generationRequest(modelId), token).path("data").path("id").asText();
        when(aiImageClient.generate(any())).thenReturn(large);
        aiGenerationService.runTask(userId, id);
        assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(aiGenerationService.task(userId, id).getImage().getSize()).isEqualTo(large.length);
        verify(aiImageClient, times(1)).generate(any());
        assertThat(imageFileService.quota(userId).getUsed()).isEqualTo(1);

        String tooLarge = post("/api/app/generations", generationRequest(modelId), token).path("data").path("id").asText();
        when(aiImageClient.generate(any())).thenReturn(new byte[20 * 1024 * 1024 + 1]);
        aiGenerationService.runTask(userId, tooLarge);
        assertThat(aiGenerationService.task(userId, tooLarge).getStatus()).isEqualTo("FAILED");
        assertThat(aiGenerationService.task(userId, tooLarge).getErrorMessage()).contains("20MB");
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
        assertThat(imageFileService.quota(userId).getUsed()).isEqualTo(1);
    }


    @Test
    void aiSaveFailureReleasesQuotaAndNeverReplaysGeneration() throws Exception {
        register("retry-ai", "retry-ai@example.test");
        String token = login("retry-ai");
        long userId = userMapper.findByUsername("retry-ai").getId();
        var request = generationRequest(enableAiModel());
        String id = post("/api/app/generations", request, token).path("data").path("id").asText();
        when(aiImageClient.generate(any())).thenReturn(png());
        doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated insert failure")).when(imageMapper).insert(any(ImageFile.class));
        aiGenerationService.runTask(userId, id);
        assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("FAILED");
        assertThat(get("/api/app/images/quota", token).path("data").path("used").asInt()).isZero();
        assertThat(get("/api/app/images/quota", token).path("data").path("reserved").asInt()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_image WHERE id=?", Long.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT pending_storage_info FROM hub_ai_generation WHERE id=?", String.class, id)).isNull();
        verify(ossTemplate).delete(any(FileInfo.class));
        Long durationMillis = jdbcTemplate.queryForObject("SELECT duration_millis FROM hub_ai_generation WHERE id=?", Long.class, id);
        assertThat(durationMillis).isNotNull().isNotNegative();
        jdbcTemplate.update("UPDATE hub_ai_generation SET update_time=TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP) WHERE id=?", id);
        assertThat(aiGenerationService.task(userId, id).getDurationSeconds())
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(durationMillis, 3));
        reset(imageMapper);
        assertThat(post("/api/app/generations/" + id + "/retry-save", Map.of(), token).path("code").asInt()).isEqualTo(409);
        assertThat(post("/api/app/generations/" + id + "/abandon", Map.of(), token).path("code").asInt()).isEqualTo(409);
        assertThat(post("/api/app/generations", request, token).path("data").path("status").asText()).isEqualTo("FAILED");
        aiGenerationService.runTask(userId, id);
        verify(aiImageClient, times(1)).generate(any());
        verify(ossTemplate, times(1)).delete(any(FileInfo.class));
    }


    @Test
    void interruptedTasksReleaseQuotaOnUserRequestsWithoutScheduledCleanup() throws Exception {
        register("recover-ai", "recover-ai@example.test");
        String token = login("recover-ai");
        long userId = userMapper.findByUsername("recover-ai").getId();
        long modelId = enableAiModel();
        for (String state : List.of("GENERATING", "SAVING")) {
            String id = post("/api/app/generations", generationRequest(modelId), token).path("data").path("id").asText();
            jdbcTemplate.update("UPDATE hub_ai_generation SET status=?, work_token='stale', work_deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", state, id);
            // 未查询前不会主动清理；首次请求通过数据库时间与任务锁回收。
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM hub_ai_generation WHERE id=?", String.class, id)).isEqualTo(state);
            if (state.equals("GENERATING")) assertThat(get("/api/app/generations/active", token).path("data").isNull()).isTrue();
            else assertThat(get("/api/app/images/quota", token).path("data").path("reserved").asInt()).isZero();
            assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("FAILED");
            assertThat(aiGenerationService.task(userId, id).getDurationSeconds()).isNull();
            assertThat(imageFileService.quota(userId).getRemaining()).isEqualTo(100);
        }
        verifyNoInteractions(aiImageClient);
        verify(storage, never()).of(any(MultipartFile.class));
        verify(ossTemplate, never()).delete(any(FileInfo.class));

        String failed = post("/api/app/generations", generationRequest(modelId), token).path("data").path("id").asText();
        when(aiImageClient.generate(any())).thenThrow(new IllegalStateException("provider-secret-should-not-escape"));
        aiGenerationService.runTask(userId, failed);
        assertThat(aiGenerationService.task(userId, failed).getStatus()).isEqualTo("FAILED");
        assertThat(aiGenerationService.task(userId, failed).getDurationSeconds()).isNotNull().isNotNegative();
        assertThat(get("/api/app/generations/" + failed, token).toString()).doesNotContain("provider-secret");
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
    }

    @Test
    void aiConcurrentSubmissionCannotReserveTheSameLastQuota() throws Exception {
        register("last-ai", "last-ai@example.test");
        String token = login("last-ai");
        long userId = userMapper.findByUsername("last-ai").getId();
        long modelId = enableAiModel();
        jdbcTemplate.update("UPDATE hub_settings SET config_value='1' WHERE config_key='upload.free-total'");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return post("/api/app/generations", generationRequest(modelId), token); });
            var second = executor.submit(() -> { start.await(); return post("/api/app/generations", generationRequest(modelId), token); });
            start.countDown();
            var results = List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> result.path("code").asInt() == 200)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_ai_generation WHERE active_user_id=?", Long.class, userId)).isEqualTo(1);
            assertThat(upload(token, "last.png", png()).path("code").asInt()).isEqualTo(40301);
        } finally { executor.shutdownNow(); }
    }


    @Test
    void invalidGeneratedBytesReleaseQuotaBeforeCloudUpload() throws Exception {
        register("invalid-ai", "invalid-ai@example.test");
        String token = login("invalid-ai");
        long userId = userMapper.findByUsername("invalid-ai").getId();
        String id = post("/api/app/generations", generationRequest(enableAiModel()), token).path("data").path("id").asText();
        when(aiImageClient.generate(any())).thenReturn(java.util.Arrays.copyOf(png(), 8));
        aiGenerationService.runTask(userId, id);
        assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("FAILED");
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
        verify(storage, never()).of(any(MultipartFile.class));
    }

    @Test
    void duplicateDiscoveredInsideQuotaLockReturnsOriginalEvenWhenQuotaIsFull() {
        register("idem-ai", "idem-ai@example.test");
        String token = login("idem-ai");
        long userId = userMapper.findByUsername("idem-ai").getId();
        jdbcTemplate.update("UPDATE hub_settings SET config_value='1' WHERE config_key='upload.free-total'");
        var request = generationRequest(enableAiModel());
        String id = post("/api/app/generations", request, token).path("data").path("id").asText();
        assertThat(imageFileService.quota(userId).getRemaining()).isZero();
        // 模拟首次查询之后其他请求已经提交；锁内发现重复时不能再按新任务检查积分。
        String duplicate = uploadQuotaCache.reserveGeneration(userId, 1, () -> id, () -> { throw new AssertionError("must not create again"); });
        assertThat(duplicate).isEqualTo(id);
        assertThat(post("/api/app/generations", request, token).path("data").path("id").asText()).isEqualTo(id);
    }


    @Test
    void failedCloudCompensationIsRetainedWithoutAutomaticCleanup() throws Exception {
        register("cleanup-ai", "cleanup-ai@example.test");
        String token = login("cleanup-ai");
        long userId = userMapper.findByUsername("cleanup-ai").getId();
        String id = post("/api/app/generations", generationRequest(enableAiModel()), token).path("data").path("id").asText();
        when(aiImageClient.generate(any())).thenReturn(png());
        doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated rollback")).when(imageMapper).insert(any(ImageFile.class));
        when(ossTemplate.delete(any(FileInfo.class))).thenReturn(false);
        aiGenerationService.runTask(userId, id);
        String pending = jdbcTemplate.queryForObject("SELECT pending_storage_info FROM hub_ai_generation WHERE id=?", String.class, id);
        assertThat(pending).isNotBlank();
        assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("FAILED");
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
        aiGenerationService.active(userId);
        aiGenerationService.runTask(userId, id);
        assertThat(jdbcTemplate.queryForObject("SELECT pending_storage_info FROM hub_ai_generation WHERE id=?", String.class, id)).isEqualTo(pending);
        verify(ossTemplate, times(1)).delete(any(FileInfo.class));
        verify(storage, times(1)).of(any(MultipartFile.class));
        verify(aiImageClient, times(1)).generate(any());
    }


    /** 超时不等于线程已退出；仍持锁的任务不能被查询请求释放或覆盖。 */
    @Test
    void onDemandRecoveryDoesNotInterruptLiveWorkerAndPreservesCloudLocation() throws Exception {
        register("crash-ai", "crash-ai@example.test");
        String token = login("crash-ai");
        long userId = userMapper.findByUsername("crash-ai").getId();
        String id = post("/api/app/generations", generationRequest(enableAiModel()), token).path("data").path("id").asText();
        String pending = objectMapper.writeValueAsString(stored);
        jdbcTemplate.update("UPDATE hub_ai_generation SET status='SAVING', pending_storage_info=?, work_token='crashed', work_deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", pending, id);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var acquired = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var live = executor.submit(() -> {
                var taskLock = redissonClient.getLock("image-hub:generation:" + id);
                assertThat(taskLock.tryLock()).isTrue();
                acquired.countDown();
                try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                finally { taskLock.unlock(); }
                return null;
            });
            assertThat(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("SAVING");
            assertThat(imageFileService.quota(userId).getReserved()).isEqualTo(1);
            release.countDown();
            live.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("FAILED");
            assertThat(imageFileService.quota(userId).getReserved()).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT pending_storage_info FROM hub_ai_generation WHERE id=?", String.class, id)).isEqualTo(pending);
            verify(ossTemplate, never()).delete(any(FileInfo.class));
            verifyNoInteractions(aiImageClient);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** 普通上传占用积分锁时，AI 保存等待释放后成功，不重新生成、不删除已上传结果。 */
    @Test
    void aiSaveWaitsForQuotaLockAndCommitsOnce() throws Exception {
        register("wait-ai", "wait-ai@example.test");
        String token = login("wait-ai");
        long userId = userMapper.findByUsername("wait-ai").getId();
        String id = post("/api/app/generations", generationRequest(enableAiModel()), token).path("data").path("id").asText();
        when(aiImageClient.generate(any())).thenReturn(png());
        var mutex = new java.util.concurrent.locks.ReentrantLock();
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var quotaLock = mock(org.redisson.api.RLock.class);
        when(quotaLock.tryLock()).thenAnswer(call -> mutex.tryLock());
        when(quotaLock.tryLock(anyLong(), eq(java.util.concurrent.TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            assertThat(call.<Long>getArgument(0)).isBetween(1L, 10_000L);
            waiting.countDown();
            return mutex.tryLock(call.getArgument(0), call.getArgument(1));
        });
        when(quotaLock.isHeldByCurrentThread()).thenAnswer(call -> mutex.isHeldByCurrentThread());
        doAnswer(call -> { mutex.unlock(); return null; }).when(quotaLock).unlock();
        when(redissonClient.getLock("image-hub:quota:{" + userId + "}:lock")).thenReturn(quotaLock);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        mutex.lock();
        try {
            var saving = executor.submit(() -> aiGenerationService.runTask(userId, id));
            assertThat(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(saving.isDone()).isFalse();
            assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("SAVING");
            verify(ossTemplate, never()).delete(any(FileInfo.class));
            mutex.unlock();
            saving.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("SUCCEEDED");
            assertThat(imageFileService.quota(userId).getUsed()).isEqualTo(1);
            assertThat(imageFileService.quota(userId).getReserved()).isZero();
            verify(aiImageClient, times(1)).generate(any());
            verify(storage, times(1)).of(any(MultipartFile.class));
            verify(ossTemplate, never()).delete(any(FileInfo.class));
        } finally {
            if (mutex.isHeldByCurrentThread()) mutex.unlock();
            executor.shutdownNow();
        }
    }

    /** 等待有上限，超时或中断不进入持久化回调、不释放其他线程的锁。 */
    @Test
    void aiQuotaWaitTimeoutAndInterruptionDoNotPersist() throws Exception {
        var quotaLock = mock(org.redisson.api.RLock.class);
        when(redissonClient.getLock("image-hub:quota:{123}:lock")).thenReturn(quotaLock);
        java.util.function.Supplier<Object> persist = () -> { throw new AssertionError("must not persist"); };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> uploadQuotaCache.completeGeneration(123, 50_000, persist))
                .isInstanceOf(com.aurora.starter.webmvc.exception.BizException.class).hasMessageContaining("等待超时");
        verify(quotaLock).tryLock(10_000, java.util.concurrent.TimeUnit.MILLISECONDS);
        when(quotaLock.tryLock(0, java.util.concurrent.TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> uploadQuotaCache.completeGeneration(123, 0, persist))
                    .isInstanceOf(com.aurora.starter.webmvc.exception.BizException.class).hasMessageContaining("已中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(quotaLock, never()).unlock();
    }

    /** 分页批量取图必须保留排序与总数，且不能返回已删除或其他用户的图片。 */
    @Test
    @SuppressWarnings("unchecked")
    void aiHistoryLoadsImagesOnceAndKeepsOwnershipAndPagination() throws Exception {
        register("history-ai", "history-ai@example.test");
        String token = login("history-ai");
        long userId = userMapper.findByUsername("history-ai").getId();
        register("history-other", "history-other@example.test");
        long otherUserId = userMapper.findByUsername("history-other").getId();
        long modelId = enableAiModel();
        when(aiImageClient.generate(any())).thenReturn(png());
        var ids = new java.util.ArrayList<String>();
        for (int index = 0; index < 3; index++) {
            String id = post("/api/app/generations", generationRequest(modelId), token).path("data").path("id").asText();
            aiGenerationService.runTask(userId, id);
            ids.add(id);
        }
        imageMapper.deleteOwned(userId, ids.get(0));
        jdbcTemplate.update("UPDATE hub_image SET user_id=? WHERE id=?", otherUserId, ids.get(1));
        clearInvocations(imageMapper);
        var all = aiGenerationService.history(userId, 1, 12);
        assertThat(all.getTotal()).isEqualTo(3);
        assertThat(all.getRecords()).hasSize(3);
        for (var task : all.getRecords()) {
            if (task.getId().equals(ids.get(2))) assertThat(task.getImage().getId()).isEqualTo(ids.get(2));
            else assertThat(task.getImage()).isNull();
        }
        verify(imageMapper, times(1)).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(imageMapper, never()).findOwned(anyLong(), anyString());
        var first = aiGenerationService.history(userId, 1, 2);
        var second = aiGenerationService.history(userId, 2, 2);
        assertThat(first.getTotal()).isEqualTo(3);
        assertThat(first.getPages()).isEqualTo(2);
        assertThat(first.getRecords()).extracting(com.aurora.imagehub.model.vo.GenerationVO::getId)
                .containsExactlyElementsOf(all.getRecords().subList(0, 2).stream().map(com.aurora.imagehub.model.vo.GenerationVO::getId).toList());
        assertThat(second.getRecords()).extracting(com.aurora.imagehub.model.vo.GenerationVO::getId)
                .containsExactly(all.getRecords().get(2).getId());
        clearInvocations(imageMapper);
        assertThat(aiGenerationService.history(otherUserId, 1, 12).getRecords()).isEmpty();
        verify(imageMapper, never()).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    /** 上游拒绝详情沿现有字段落库并返回，同时释放积分且不发起上传。 */
    @Test
    void aiProviderErrorIsReturnedInTaskAndHistory() {
        register("provider-error", "provider-error@example.test");
        String token = login("provider-error");
        long userId = userMapper.findByUsername("provider-error").getId();
        String errorMessage = "poll failed: 451 {\"error_code\":\"image_unsafe\",\"message\":\"The generated images appear to be unsafe.\"}\n" + "原始消息".repeat(100);
        when(aiImageClient.generate(any())).thenThrow(new com.aurora.starter.webmvc.exception.BizException(502, errorMessage));
        String id = post("/api/app/generations", generationRequest(enableAiModel()), token).path("data").path("id").asText();
        aiGenerationService.runTask(userId, id);
        var task = get("/api/app/generations/" + id, token);
        assertThat(task.path("code").asInt()).isEqualTo(200);
        assertThat(task.path("data").path("status").asText()).isEqualTo("FAILED");
        assertThat(task.path("data").path("errorMessage").asText()).isEqualTo(errorMessage);
        assertThat(get("/api/app/generations?page=1&pageSize=12", token).path("data").path("records").get(0)
                .path("errorMessage").asText()).isEqualTo(errorMessage);
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
        verify(aiImageClient, times(1)).generate(any());
        verify(storage, never()).of(any(MultipartFile.class));
    }

    /** 参考图仅缓存到 Redis，幂等重试不重复缓存，后台读取原图后清理且只对生成结果扣额。 */
    @Test
    void referenceSubmissionIsDurableIdempotentAndChargedOnlyForResult() throws Exception {
        register("reference-ai", "reference-ai@example.test");
        String token = login("reference-ai");
        long userId = userMapper.findByUsername("reference-ai").getId();
        var request = generationRequest(enableAiModel());
        var submitted = submitReference(token, request, png(), 1);
        assertThat(submitted.path("code").asInt()).isEqualTo(200);
        String id = submitted.path("data").path("id").asText();
        var saved = aiGenerationMapper.selectById(id);
        assertThat(saved.getReferenceImageSource()).isEqualTo("redis:image/png");
        String key = "image-hub:reference:{" + userId + "}:" + id;
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png());
        assertThat(referenceState.get(key)).isEqualTo(png());
        assertThat(saved.getImagesPath()).isEqualTo("/v1/images/edits");
        assertThat(imageFileService.quota(userId).getUsed()).isZero();
        assertThat(imageFileService.quota(userId).getReserved()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_image WHERE user_id=?", Long.class, userId)).isZero();
        assertThat(submitReference(token, request, png(), 1).path("data").path("id").asText()).isEqualTo(id);
        verify(storage, never()).of(any(MultipartFile.class));
        assertThat(referenceState.get(key)).isEqualTo(png());
        when(aiImageClient.generate(any())).thenReturn(png());
        aiGenerationService.runTask(userId, id);
        assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(referenceState).doesNotContainKey(key);
        assertThat(aiGenerationMapper.selectById(id).getReferenceImageSource()).isEqualTo("redis:image/png");
        verify(storage, times(1)).of(any(MultipartFile.class));
        verify(aiImageClient).generate(argThat(task -> dataUrl.equals(task.getReferenceImageSource())
                && "/v1/images/edits".equals(task.getImagesPath())));
        assertThat(imageFileService.quota(userId).getUsed()).isEqualTo(1);
        verify(ossTemplate, never()).delete(any(FileInfo.class));
    }

    /** 非法文件不得缓存或扣额；写库失败即时移除参考图，不触发云上传或云删除。 */
    @Test
    void invalidReferencesAndFailedTaskInsertDoNotChargeQuota() throws Exception {
        register("invalid-ref", "invalid-ref@example.test");
        String token = login("invalid-ref");
        long userId = userMapper.findByUsername("invalid-ref").getId();
        long modelId = enableAiModel();
        assertThat(submitReference(null, generationRequest(modelId), png(), 1).path("code").asInt()).isEqualTo(401);
        assertThat(submitReference(token, generationRequest(modelId), png(), 2).path("code").asInt()).isEqualTo(400);
        assertThat(submitReference(token, generationRequest(modelId), new byte[0], 1).path("code").asInt()).isEqualTo(400);
        assertThat(submitReference(token, generationRequest(modelId), "fake image".getBytes(), 1).path("code").asInt()).isEqualTo(400);
        assertThat(submitReference(token, generationRequest(modelId), new byte[10 * 1024 * 1024 + 1], 1).path("code").asInt()).isEqualTo(413);
        verify(storage, never()).of(any(MultipartFile.class));

        doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated reference task insert failure"))
                .when(aiGenerationMapper).insert(any(com.aurora.imagehub.model.entity.AiGeneration.class));
        assertThat(submitReference(token, generationRequest(modelId), png(), 1).path("code").asInt()).isNotEqualTo(200);
        verify(storage, never()).of(any(MultipartFile.class));
        verify(ossTemplate, never()).delete(any(FileInfo.class));
        assertThat(referenceState).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_ai_generation WHERE user_id=?", Long.class, userId)).isZero();
        assertThat(imageFileService.quota(userId).getUsed()).isZero();
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
    }

    /** 参考图丢失不能降级纯文生图；模型拒绝时也要删除缓存并释放预占。 */
    @Test
    void expiredAndFailedReferencesReleaseQuotaWithoutCloudStorage() throws Exception {
        register("expired-ref", "expired-ref@example.test");
        String token = login("expired-ref");
        long userId = userMapper.findByUsername("expired-ref").getId();
        long modelId = enableAiModel();
        for (boolean expired : new boolean[]{true, false}) {
            String id = submitReference(token, generationRequest(modelId), png(), 1).path("data").path("id").asText();
            String key = "image-hub:reference:{" + userId + "}:" + id;
            assertThat(referenceState).containsKey(key);
            if (expired) referenceState.remove(key);
            else when(aiImageClient.generate(any())).thenThrow(new com.aurora.starter.webmvc.exception.BizException(502, "model refused"));
            aiGenerationService.runTask(userId, id);
            var task = aiGenerationService.task(userId, id);
            assertThat(task.getStatus()).isEqualTo("FAILED");
            assertThat(task.getErrorMessage()).contains(expired ? "参考图已过期或不可用" : "model refused");
            assertThat(referenceState).doesNotContainKey(key);
            assertThat(imageFileService.quota(userId).getUsed()).isZero();
            assertThat(imageFileService.quota(userId).getReserved()).isZero();
        }
        verify(aiImageClient, times(1)).generate(any());
        verify(storage, never()).of(any(MultipartFile.class));
    }

    /** 编辑复用本人原图，不重复上传；跨用户、已删除、超限和双参考图请求不能预占积分。 */
    @Test
    void editSavedImageReusesOwnedSourceAndRejectsInvalidReferences() throws Exception {
        register("edit-ai", "edit-ai@example.test");
        String token = login("edit-ai");
        long userId = userMapper.findByUsername("edit-ai").getId();
        long modelId = enableAiModel();
        when(aiImageClient.generate(any())).thenReturn(png());
        String sourceId = post("/api/app/generations", generationRequest(modelId), token).path("data").path("id").asText();
        aiGenerationService.runTask(userId, sourceId);
        ImageFile source = imageMapper.findOwned(userId, sourceId);
        assertThat(source).isNotNull();
        clearInvocations(storage, aiImageClient);

        var request = new java.util.HashMap<String, Object>(generationRequest(modelId));
        request.put("referenceImageId", sourceId);
        register("edit-other", "edit-other@example.test");
        String otherToken = login("edit-other");
        assertThat(post("/api/app/generations", request, otherToken).path("code").asInt()).isEqualTo(404);
        assertThat(submitReference(token, request, png(), 1).path("code").asInt()).isEqualTo(400);
        jdbcTemplate.update("UPDATE hub_image SET deleted=1 WHERE id=?", sourceId);
        assertThat(post("/api/app/generations", request, token).path("code").asInt()).isEqualTo(404);
        jdbcTemplate.update("UPDATE hub_image SET deleted=0, size=? WHERE id=?", 10 * 1024 * 1024 + 1, sourceId);
        assertThat(post("/api/app/generations", request, token).path("code").asInt()).isEqualTo(413);
        jdbcTemplate.update("UPDATE hub_image SET size=?, type='GIF' WHERE id=?", source.getSize(), sourceId);
        assertThat(post("/api/app/generations", request, token).path("code").asInt()).isEqualTo(400);
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
        assertThat(referenceState).isEmpty();
        verifyNoInteractions(storage, aiImageClient);
        jdbcTemplate.update("UPDATE hub_image SET type='PNG' WHERE id=?", sourceId);

        var submitted = post("/api/app/generations", request, token);
        assertThat(submitted.path("code").asInt()).isEqualTo(200);
        String id = submitted.path("data").path("id").asText();
        assertThat(post("/api/app/generations", request, token).path("data").path("id").asText()).isEqualTo(id);
        assertThat(aiGenerationMapper.selectById(id).getReferenceImageSource()).isEqualTo(source.getUrl());
        assertThat(aiGenerationMapper.selectById(id).getImagesPath()).isEqualTo("/v1/images/edits");
        verifyNoInteractions(storage);
        aiGenerationService.runTask(userId, id);
        assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("SUCCEEDED");
        verify(aiImageClient).generate(argThat(task -> source.getUrl().equals(task.getReferenceImageSource())
                && "/v1/images/edits".equals(task.getImagesPath())));
        verify(storage, times(1)).of(any(MultipartFile.class));
        assertThat(imageFileService.quota(userId).getUsed()).isEqualTo(2);
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
        assertThat(referenceState).isEmpty();
    }

    /** 构造与浏览器一致的 JSON 参数 part 和单张图片 part，不访问真实云端。 */
    private JsonNode submitReference(String token, Map<String, Object> param, byte[] bytes, int count) {
        var body = new LinkedMultiValueMap<String, Object>();
        var jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("param", new HttpEntity<>(param, jsonHeaders));
        for (int i = 0; i < count; i++) {
            body.add("reference", new ByteArrayResource(bytes) {
                @Override public String getFilename() { return "reference.png"; }
            });
        }
        var requestHeaders = headers(token);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        return testRestTemplate.postForObject("/api/app/generations", new HttpEntity<>(body, requestHeaders), JsonNode.class);
    }

    /** 测试模型使用虚构 Key，不触发真实供应商请求。 */
    private long enableAiModel() {
        long id = jdbcTemplate.queryForObject("SELECT id FROM hub_ai_model WHERE name='GPT-Image-2'", Long.class);
        jdbcTemplate.update("UPDATE hub_ai_model SET enabled=1, deleted=0, api_key_ciphertext=? WHERE id=?",
                modelKeyCipher.encrypt("test-ai-key"), id);
        return id;
    }

    private Map<String, Object> generationRequest(long modelId) {
        return Map.of("requestId", java.util.UUID.randomUUID().toString(), "modelId", modelId,
                "prompt", "海边书店", "size", "1024x1024", "quality", "medium");
    }

    private void register(String username, String email) {
        assertThat(post("/api/app/auth/email-code", Map.of("email", email), null).path("code").asInt()).isEqualTo(200);
        assertThat(post("/api/app/auth/register", Map.of("username", username, "email", email, "password", "secret123", "code", "654321"), null)
                .path("code").asInt()).isEqualTo(200);
    }

    @Test
    void registrationInitializesQuotaWithoutOverwritingItAndSurvivesRedisFailure() {
        register("new-quota", "new-quota@example.test");
        long id = userMapper.findByUsername("new-quota").getId();
        String key = "image-hub:quota:{" + id + "}";
        assertThat(quotaState.get(key)).isEqualTo("u:0");
        quotaState.put(key, "u:13");
        assertThat(post("/api/app/auth/register", Map.of("username", "new-quota", "email", "new-quota@example.test",
                "password", "secret123", "code", "654321"), null).path("code").asInt()).isEqualTo(409);
        assertThat(quotaState.get(key)).isEqualTo("u:13");
        when(redissonClient.getBucket(anyString(), eq(org.redisson.client.codec.StringCodec.INSTANCE)))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        register("redis-down", "redis-down@example.test");
        assertThat(userMapper.findByUsername("redis-down")).isNotNull();
        assertThat(login("redis-down")).isNotBlank();
    }

    /** 流水只含真实成功消耗；任务重试、防越权、分页、删图后保留和 Redis 恢复共同验证。 */
    @Test
    void quotaUsageRecordsSuccessfulConsumptionWithOwnershipAndPagination() throws Exception {
        register("usage", "usage@example.test");
        String token = login("usage");
        long userId = userMapper.findByUsername("usage").getId();
        assertThat(get("/api/app/quota/records", null).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/app/quota/records", token).path("data").path("total").asInt()).isZero();
        String uploadId = upload(token, "first.png", png()).path("data").path("id").asText();
        assertThat(get("/api/app/quota/records?page=0", token).path("code").asInt()).isEqualTo(400);
        assertThat(get("/api/app/quota/records?pageSize=51", token).path("code").asInt()).isEqualTo(400);
        var first = get("/api/app/quota/records", token).path("data").path("records").get(0);
        assertThat(first.path("scene").asText()).isEqualTo("IMAGE_UPLOAD");
        assertThat(first.path("bizId").asText()).isEqualTo(uploadId);
        assertThat(first.path("amount").asInt()).isEqualTo(1);
        assertThat(first.path("description").asText()).isEqualTo("first.png");
        assertThat(first.path("createTime").asText()).isNotBlank();
        assertThat(first.toString()).doesNotContain("userId", "storageInfo");

        var request = generationRequest(enableAiModel());
        String id = post("/api/app/generations", request, token).path("data").path("id").asText();
        assertThat(get("/api/app/quota/records", token).path("data").path("total").asInt()).isEqualTo(1);
        when(aiImageClient.generate(any())).thenReturn(png());
        aiGenerationService.runTask(userId, id);
        aiGenerationService.runTask(userId, id);
        assertThat(post("/api/app/generations", request, token).path("data").path("id").asText()).isEqualTo(id);
        var page = get("/api/app/quota/records?page=1&pageSize=1", token).path("data");
        assertThat(page.path("total").asInt()).isEqualTo(2);
        assertThat(page.path("pages").asInt()).isEqualTo(2);
        assertThat(page.path("records").get(0).path("scene").asText()).isEqualTo("AI_GENERATION");
        assertThat(page.path("records").get(0).path("bizId").asText()).isEqualTo(id);
        assertThat(get("/api/app/quota/records?page=2&pageSize=1", token).path("data").path("records").get(0)
                .path("bizId").asText()).isEqualTo(uploadId);
        register("usage-other", "usage-other@example.test");
        assertThat(get("/api/app/quota/records?userId=" + userId, login("usage-other"))
                .path("data").path("total").asInt()).isZero();

        when(aiImageClient.generate(any())).thenThrow(new IllegalStateException("simulated provider failure"));
        String failedId = post("/api/app/generations", generationRequest(enableAiModel()), token).path("data").path("id").asText();
        aiGenerationService.runTask(userId, failedId);
        assertThat(delete(uploadId, token).path("code").asInt()).isEqualTo(200);
        quotaState.clear();
        assertThat(imageFileService.quota(userId).getUsed()).isEqualTo(2);
        assertThat(get("/api/app/quota/records", token).path("data").path("total").asInt()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO hub_quota_usage (user_id,scene,biz_id,amount,description) VALUES (?,?,?,?,?)",
                userId, "IMAGE_UPLOAD", uploadId, 1, "duplicate")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    /** 写流水失败必须回滚计费图片与 AI 成功终态，积分恢复且补偿云文件。 */
    @Test
    void quotaUsageWriteFailureRollsBackUploadAndGeneration() throws Exception {
        register("usage-fail", "usage-fail@example.test");
        String token = login("usage-fail");
        long userId = userMapper.findByUsername("usage-fail").getId();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated ledger failure"))
                .when(quotaUsageMapper).insert(any(com.aurora.imagehub.model.entity.QuotaUsage.class));
        assertThat(upload(token, "failed.png", png()).path("code").asInt()).isEqualTo(500);
        assertThat(imageFileService.quota(userId).getUsed()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_image WHERE user_id=?", Long.class, userId)).isZero();
        String id = post("/api/app/generations", generationRequest(enableAiModel()), token).path("data").path("id").asText();
        when(aiImageClient.generate(any())).thenReturn(png());
        aiGenerationService.runTask(userId, id);
        assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("FAILED");
        assertThat(imageFileService.quota(userId).getUsed()).isZero();
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_image WHERE user_id=?", Long.class, userId)).isZero();
        assertThat(get("/api/app/quota/records", token).path("data").path("total").asInt()).isZero();
        verify(ossTemplate, times(2)).delete(any(FileInfo.class));
    }

    /** 模型定价贯穿预留、快照结算、流水及缓存恢复；失败和中断释放完整积分。 */
    @Test
    void modelPointsCostIsSnapshottedAndChargedExactlyOnce() throws Exception {
        register("priced-ai", "priced-ai@example.test");
        String token = login("priced-ai");
        long userId = userMapper.findByUsername("priced-ai").getId();
        long modelId = enableAiModel();
        jdbcTemplate.update("UPDATE hub_settings SET config_value='10' WHERE config_key='upload.free-total'");
        jdbcTemplate.update("UPDATE hub_ai_model SET points_cost=6 WHERE id=?", modelId);
        assertThat(get("/api/app/generations/models", token).path("data").get(0).path("pointsCost").asInt()).isEqualTo(6);

        var request = new java.util.HashMap<>(generationRequest(modelId));
        request.put("pointsCost", 1); // 客户端伪造费用不能覆盖服务端配置。
        String id = post("/api/app/generations", request, token).path("data").path("id").asText();
        assertThat(imageFileService.quota(userId).getReserved()).isEqualTo(6);
        assertThat(imageFileService.quota(userId).getRemaining()).isEqualTo(4);
        assertThat(upload(token, "normal.png", png()).path("code").asInt()).isEqualTo(200);
        assertThat(imageFileService.quota(userId).getRemaining()).isEqualTo(3);
        jdbcTemplate.update("UPDATE hub_ai_model SET points_cost=3 WHERE id=?", modelId);
        assertThat(post("/api/app/generations", request, token).path("data").path("id").asText()).isEqualTo(id);
        when(aiImageClient.generate(any())).thenReturn(png());
        aiGenerationService.runTask(userId, id);
        aiGenerationService.runTask(userId, id);
        assertThat(aiGenerationService.task(userId, id).getStatus()).isEqualTo("SUCCEEDED");
        verify(aiImageClient, times(1)).generate(argThat(task -> task.getPointsCost() == 6));
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
        assertThat(imageFileService.quota(userId).getUsed()).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM hub_quota_usage WHERE biz_id=?", Integer.class, id)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(amount) FROM hub_quota_usage WHERE user_id=?", Long.class, userId)).isEqualTo(7);
        assertThat(delete(id, token).path("code").asInt()).isEqualTo(200);
        quotaState.clear();
        assertThat(imageFileService.quota(userId).getUsed()).isEqualTo(7);

        jdbcTemplate.update("UPDATE hub_ai_model SET points_cost=4 WHERE id=?", modelId);
        assertThat(post("/api/app/generations", generationRequest(modelId), token).path("code").asInt()).isEqualTo(40301);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_ai_generation WHERE user_id=?", Long.class, userId)).isEqualTo(1);
        jdbcTemplate.update("UPDATE hub_ai_model SET points_cost=3 WHERE id=?", modelId);
        String failed = post("/api/app/generations", generationRequest(modelId), token).path("data").path("id").asText();
        assertThat(imageFileService.quota(userId).getReserved()).isEqualTo(3);
        assertThat(imageFileService.quota(userId).getRemaining()).isZero();
        assertThat(upload(token, "blocked.png", png()).path("code").asInt()).isEqualTo(40301);
        when(aiImageClient.generate(any())).thenThrow(new IllegalStateException("simulated provider failure"));
        aiGenerationService.runTask(userId, failed);
        assertThat(aiGenerationService.task(userId, failed).getStatus()).isEqualTo("FAILED");
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
        assertThat(imageFileService.quota(userId).getRemaining()).isEqualTo(3);

        String interrupted = post("/api/app/generations", generationRequest(modelId), token).path("data").path("id").asText();
        jdbcTemplate.update("UPDATE hub_ai_generation SET status='GENERATING', work_token='stale', work_deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", interrupted);
        assertThat(imageFileService.quota(userId).getReserved()).isZero();
        assertThat(imageFileService.quota(userId).getRemaining()).isEqualTo(3);
        assertThat(get("/api/app/quota/records", token).path("data").path("total").asInt()).isEqualTo(2);
    }

    private String login(String username) {
        JsonNode result = post("/api/app/auth/login", Map.of("username", username, "password", "secret123"), null);
        assertThat(result.path("code").asInt()).isEqualTo(200);
        assertThat(result.path("data").path("expiresIn").asLong()).isBetween(259190L, 259200L);
        return result.path("data").path("token").asText();
    }

    private HttpHeaders headers(String token) {
        var headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return headers;
    }

    private JsonNode get(String path, String token) {
        return testRestTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class).getBody();
    }

    private JsonNode post(String path, Object body, String token) {
        return testRestTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class).getBody();
    }

    private JsonNode delete(String id, String token) {
        return testRestTemplate.exchange("/api/app/images/" + id, HttpMethod.DELETE, new HttpEntity<>(headers(token)), JsonNode.class).getBody();
    }

    private JsonNode upload(String token, String filename, byte[] bytes) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        var headers = headers(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return testRestTemplate.postForObject("/api/app/images", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
