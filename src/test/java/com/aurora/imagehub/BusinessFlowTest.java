package com.aurora.imagehub;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.hutool.crypto.digest.BCrypt;
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
        "platform.security.is-log=false"
})
@Import(BusinessFlowTest.MemorySessions.class)
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
    @Autowired ObjectMapper objectMapper;
    @Autowired SaTokenDao saTokenDao;
    @MockitoBean RedissonClient redissonClient;
    @MockitoBean LettuceConnectionFactory lettuceConnectionFactory;
    @MockitoBean AttemptLimiter attemptLimiter;
    @MockitoBean MailVerificationService mailVerificationService;
    @MockitoBean OssTemplate ossTemplate;
    @MockitoSpyBean com.aurora.imagehub.mapper.ImageMapper imageMapper;

    private final Map<String, String> codes = new ConcurrentHashMap<>();
    private FileStorageService storage;
    private FileInfo stored;
    private final Map<String, String> quotaState = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        SaManager.setSaTokenDao(saTokenDao);
        jdbcTemplate.update("DELETE FROM hub_image");
        jdbcTemplate.update("DELETE FROM hub_user");
        codes.clear();
        quotaState.clear();
        var locks = new ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>();
        when(redissonClient.getBucket(anyString(), eq(org.redisson.client.codec.StringCodec.INSTANCE))).thenAnswer(call -> {
            String key = call.getArgument(0);
            org.redisson.api.RBucket<String> bucket = mock(org.redisson.api.RBucket.class);
            when(bucket.get()).thenAnswer(ignored -> quotaState.get(key));
            when(bucket.setIfAbsent(anyString())).thenAnswer(write -> quotaState.putIfAbsent(key, write.getArgument(0)) == null);
            doAnswer(write -> { quotaState.put(key, write.getArgument(0)); return null; }).when(bucket).set(anyString());
            return bucket;
        });
        when(redissonClient.getLock(anyString())).thenAnswer(call -> {
            var mutex = locks.computeIfAbsent(call.getArgument(0), ignored -> new java.util.concurrent.locks.ReentrantLock());
            org.redisson.api.RLock lock = mock(org.redisson.api.RLock.class);
            when(lock.tryLock()).thenAnswer(ignored -> mutex.tryLock());
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
        stored = new FileInfo().setPlatform("qiniu-kodo-1").setBasePath("base/").setPath("images/test/")
                .setFilename("stored.png").setUrl("https://cdn.example.test/base/images/test/stored.png")
                .setSize(100L).setContentType("image/png")
                .setCreateTime(java.util.Date.from(java.time.Instant.parse("2026-09-14T01:57:41.489Z")));
        when(storage.of(any(MultipartFile.class))).thenAnswer(call -> {
            UploadPretreatment upload = mock(UploadPretreatment.class, Answers.RETURNS_SELF);
            when(upload.upload()).thenReturn(stored);
            return upload;
        });
        when(storage.exists(any(FileInfo.class))).thenReturn(true);
        when(ossTemplate.delete(any(FileInfo.class))).thenReturn(true);
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
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(100);
        // 种入 99 条新版成功记录，其中包含已删除图片；历史记录始终不计费。
        for (int i = 0; i < 99; i++) jdbcTemplate.update("""
                INSERT INTO hub_image (id,user_id,name,url,type,size,width,height,storage_info,quota_charged,deleted)
                SELECT ?,user_id,name,url,type,size,width,height,storage_info,1,1 FROM hub_image WHERE id = ?
                """, java.util.UUID.randomUUID().toString(), legacyId);
        quotaState.clear();
        assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isEqualTo(1);
        String lastId = upload(token, "last.png", png()).path("data").path("id").asText();
        assertThat(lastId).isNotBlank();
        assertThat(delete(lastId, token).path("code").asInt()).isEqualTo(200);
        quotaState.clear();
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
        String key = "image-hub:quota:v1:{" + userId + "}";
        quotaState.put(key, "99:interrupted-upload");
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
        quotaState.put(key, "98:another-interrupted-upload");
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
        quotaState.put("image-hub:quota:v1:{" + userId + "}", "1");
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
            finish.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).path("code").asInt()).isEqualTo(200);
            assertThat(get("/api/app/images/quota", token).path("data").path("remaining").asInt()).isZero();
            assertThat(upload(token, "third.png", bytes).path("code").asInt()).isEqualTo(40301);
            verify(uploadPretreatment).upload();
        } finally { finish.countDown(); executor.shutdownNow(); }
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
        String key = "image-hub:quota:v1:{" + id + "}";
        assertThat(quotaState.get(key)).isEqualTo("100");
        quotaState.put(key, "87");
        assertThat(post("/api/app/auth/register", Map.of("username", "new-quota", "email", "new-quota@example.test",
                "password", "secret123", "code", "654321"), null).path("code").asInt()).isEqualTo(409);
        assertThat(quotaState.get(key)).isEqualTo("87");
        when(redissonClient.getBucket(anyString(), eq(org.redisson.client.codec.StringCodec.INSTANCE)))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        register("redis-down", "redis-down@example.test");
        assertThat(userMapper.findByUsername("redis-down")).isNotNull();
        assertThat(login("redis-down")).isNotBlank();
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
