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
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=file:deploy/db/schema.sql",
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

    @BeforeEach
    void setup() {
        SaManager.setSaTokenDao(saTokenDao);
        jdbcTemplate.update("DELETE FROM hub_image");
        jdbcTemplate.update("DELETE FROM hub_user");
        codes.clear();
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
                .setSize(100L).setContentType("image/png");
        when(storage.of(any(MultipartFile.class))).thenAnswer(call -> {
            UploadPretreatment upload = mock(UploadPretreatment.class, Answers.RETURNS_SELF);
            when(upload.upload()).thenReturn(stored);
            return upload;
        });
        when(storage.exists(any(FileInfo.class))).thenReturn(true);
        when(ossTemplate.delete(any(FileInfo.class))).thenReturn(true);
    }

    @Test
    void registrationSessionsAndOwnershipWorkEndToEnd() throws Exception {
        assertThat(get("/api/images", null).path("code").asInt()).isEqualTo(401);
        String username = "alice";
        register(username, "Alice@example.test");
        assertThat(get("/api/auth/me", null).path("code").asInt()).isEqualTo(401);
        assertThat(userMapper.findByUsername(username).getPasswordHash()).startsWith("$2");
        assertThat(BCrypt.checkpw("secret123", userMapper.findByUsername(username).getPasswordHash())).isTrue();
        assertThat(post("/api/auth/login", Map.of("username", username, "password", "wrong123"), null).path("code").asInt()).isEqualTo(400);
        // 校验继承的通用查询正确映射 hub_user，而非按类名推导表名。
        assertThat(userAccountService.getById(userMapper.findByUsername(username).getId()).getUsername()).isEqualTo(username);
        String first = login(username);
        String second = login(username);
        assertThat(first).isNotEqualTo(second);
        assertThat(get("/api/auth/me", first).path("data").path("username").asText()).isEqualTo(username);

        JsonNode upload = upload(first, "photo.png", png());
        assertThat(upload.path("code").asInt()).isEqualTo(200);
        JsonNode image = upload.path("data");
        assertThat(image.path("width").asInt()).isEqualTo(3);
        assertThat(image.path("height").asInt()).isEqualTo(2);
        assertThat(image.has("storageInfo")).isFalse();
        ImageFile persisted = imageFileService.getById(image.path("id").asText());
        assertThat(persisted.getUserId()).isEqualTo(userMapper.findByUsername(username).getId());
        assertThat(persisted.getStorageInfo()).isNotBlank();
        assertThat(persisted.getDeleted()).isZero();
        assertThat(persisted.getCreateTime()).isNotNull();
        assertThat(persisted.getUpdateTime()).isNotNull();
        assertThat(persisted.getCreateTime().toInstant().getNano()).isZero();
        assertThat(persisted.getUpdateTime().toInstant().getNano()).isZero();
        assertThat(java.time.Instant.parse(image.path("createdAt").asText())).isEqualTo(persisted.getCreateTime().toInstant());
        var createTime = persisted.getCreateTime();
        persisted.setUpdateTime(new java.util.Date(0));
        assertThat(imageFileService.updateById(persisted)).isTrue();
        ImageFile updated = imageFileService.getById(persisted.getId());
        assertThat(updated.getCreateTime()).isEqualTo(createTime);
        assertThat(updated.getUpdateTime()).isAfter(new java.util.Date(0));
        assertThat(updated.getUpdateTime().toInstant().getNano()).isZero();
        JsonNode page = get("/api/images?search=photo&type=PNG&page=1&pageSize=1", first).path("data");
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(page.path("current").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(1);
        assertThat(page.path("pages").asInt()).isEqualTo(1);
        JsonNode stats = get("/api/images/stats", first).path("data");
        assertThat(stats.path("totalCount").asInt()).isEqualTo(1);
        assertThat(stats.path("totalBytes").asInt()).isEqualTo(png().length);
        assertThat(page.path("records").get(0).path("id")).isEqualTo(image.path("id"));
        assertThat(get("/api/images?search=absent", first).path("data").path("total").asInt()).isZero();
        assertThat(get("/api/images?pageSize=101", first).path("code").asInt()).isEqualTo(400);

        register("bob", "bob@example.test");
        String bob = login("bob");
        assertThat(get("/api/images/stats", null).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/images/stats", bob).path("data").path("totalCount").asInt()).isZero();
        assertThat(get("/api/images/stats", bob).path("data").path("totalBytes").asInt()).isZero();
        assertThat(get("/api/images", bob).path("data").path("total").asInt()).isZero();
        assertThat(delete(image.path("id").asText(), bob).path("code").asInt()).isEqualTo(404);
        verify(ossTemplate, never()).delete(any(FileInfo.class));

        assertThat(post("/api/auth/logout", Map.of(), first).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/auth/me", first).path("code").asInt()).isEqualTo(401);
        assertThat(get("/api/auth/me", second).path("code").asInt()).isEqualTo(200);
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
        assertThat(get("/api/images", second).path("data").path("total").asInt()).isZero();
        assertThat(get("/api/images/stats", second).path("data").path("totalBytes").asInt()).isZero();
    }

    @Test
    void invalidCodesDuplicatesAndFakeImagesAreRejected() throws Exception {
        // 普通 Param 类通过 setter 绑定 JSON，字段上的 Bean Validation 约束仍须生效。
        assertThat(post("/api/auth/email-code", Map.of("email", "invalid"), null).path("code").asInt()).isEqualTo(400);
        assertThat(post("/api/auth/register", Map.of("username", "bad name", "email", "carol@example.test",
                "password", "secret123", "code", "654321"), null).path("code").asInt()).isEqualTo(400);
        assertThat(post("/api/auth/login", Map.of("username", "carol", "password", "123"), null)
                .path("code").asInt()).isEqualTo(400);
        verify(mailVerificationService, never()).send(any());
        verify(mailVerificationService, never()).verifyAndConsume(any());
        var input = Map.of("username", "carol", "email", "carol@example.test", "password", "secret123", "code", "654321");
        assertThat(post("/api/auth/register", input, null).path("code").asInt()).isEqualTo(400);
        post("/api/auth/email-code", Map.of("email", "carol@example.test"), null);
        assertThat(post("/api/auth/register", input, null).path("code").asInt()).isEqualTo(200);
        assertThat(post("/api/auth/register", input, null).path("code").asInt()).isEqualTo(409);
        assertThat(post("/api/auth/register", Map.of("username", "other", "email", "CAROL@example.test",
                "password", "secret123", "code", "654321"), null).path("code").asInt()).isEqualTo(409);
        String token = login("carol");
        assertThat(upload(token, "fake.png", "<html>not an image</html>".getBytes()).path("code").asInt()).isEqualTo(400);
        assertThat(upload(token, "fake.jpg", png()).path("code").asInt()).isEqualTo(400);
        verify(storage, never()).of(any(MultipartFile.class));
    }

    @Test
    void failedCloudDeletionKeepsRecordAndCanBeRetried() throws Exception {
        register("david", "david@example.test");
        String token = login("david");
        String id = upload(token, "retry.png", png()).path("data").path("id").asText();
        when(ossTemplate.delete(any(FileInfo.class))).thenReturn(false);
        assertThat(delete(id, token).path("code").asInt()).isEqualTo(502);
        assertThat(get("/api/images", token).path("data").path("total").asInt()).isEqualTo(1);
        when(storage.exists(any(FileInfo.class))).thenReturn(false);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_image WHERE id = ?", Integer.class, id)).isZero();
        assertThat(delete(id, token).path("code").asInt()).isEqualTo(200);
        assertThat(get("/api/images", token).path("data").path("total").asInt()).isZero();
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
        JsonNode firstPage = get("/api/images?page=1&pageSize=2", token).path("data");
        JsonNode secondPage = get("/api/images?page=2&pageSize=2", token).path("data");
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
        JsonNode beyond = get("/api/images?page=3&pageSize=2", token).path("data");
        assertThat(beyond.path("records").size()).isZero();
        assertThat(beyond.path("total").asInt()).isEqualTo(4);
        assertThat(get("/api/images?type=WEBP&pageSize=1", token).path("data").path("total").asInt()).isEqualTo(1);
        assertThat(get("/api/images?search=absent", token).path("data").path("records").size()).isZero();
        assertThat(get("/api/images/stats", token).path("data").path("totalCount").asInt()).isEqualTo(4);
        assertThat(get("/api/images?page=0", token).path("code").asInt()).isEqualTo(400);
        assertThat(upload(token, "too-large.png", new byte[10 * 1024 * 1024 + 1]).path("code").asInt()).isEqualTo(413);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated database failure"))
                .when(imageMapper).insert(any(ImageFile.class));
        assertThat(upload(token, "rollback.png", png()).path("code").asInt()).isEqualTo(500);
        verify(ossTemplate).delete(stored);
        assertThat(get("/api/images", token).path("data").path("total").asInt()).isEqualTo(4);
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
        user.setUpdateTime(new java.util.Date(0));
        assertThat(userAccountService.updateById(user)).isTrue();
        var updated = userAccountService.getById(user.getId());
        assertThat(updated.getCreateTime()).isEqualTo(createTime);
        assertThat(updated.getUpdateTime()).isAfter(new java.util.Date(0));
        assertThat(updated.getUpdateTime().toInstant().getNano()).isZero();

        assertThat(userAccountService.removeById(user.getId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_user WHERE id = ?", Integer.class, user.getId())).isEqualTo(1);
        assertThat(userAccountService.getById(user.getId())).isNull();
        assertThat(userMapper.findById(user.getId())).isNull();
        assertThat(userMapper.findByUsername("archived")).isNull();
        assertThat(post("/api/auth/login", Map.of("username", "archived", "password", "secret123"), null).path("code").asInt()).isEqualTo(400);
        assertThat(get("/api/auth/me", token).path("code").asInt()).isEqualTo(401);
        assertThat(post("/api/auth/register", Map.of("username", "archived", "email", "new@example.test",
                "password", "secret123", "code", "654321"), null).path("code").asInt()).isEqualTo(409);
        assertThat(post("/api/auth/email-code", Map.of("email", "archived@example.test"), null).path("code").asInt()).isEqualTo(409);
    }

    private void register(String username, String email) {
        assertThat(post("/api/auth/email-code", Map.of("email", email), null).path("code").asInt()).isEqualTo(200);
        assertThat(post("/api/auth/register", Map.of("username", username, "email", email, "password", "secret123", "code", "654321"), null)
                .path("code").asInt()).isEqualTo(200);
    }

    private String login(String username) {
        JsonNode result = post("/api/auth/login", Map.of("username", username, "password", "secret123"), null);
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
        return testRestTemplate.exchange("/api/images/" + id, HttpMethod.DELETE, new HttpEntity<>(headers(token)), JsonNode.class).getBody();
    }

    private JsonNode upload(String token, String filename, byte[] bytes) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        var headers = headers(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return testRestTemplate.postForObject("/api/images", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
