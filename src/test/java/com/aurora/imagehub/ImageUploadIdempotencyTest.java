package com.aurora.imagehub;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.hutool.crypto.digest.BCrypt;
import com.aurora.imagehub.mapper.ImageMapper;
import com.aurora.imagehub.ratelimit.AttemptLimiter;
import com.aurora.imagehub.service.ImageFileService;
import com.aurora.starter.oss.template.OssTemplate;
import com.aurora.starter.redis.core.TwoLevelCache;
import com.aurora.starter.webmvc.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.imageio.ImageIO;

import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.upload.UploadPretreatment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 使用真实 HTTP、H2 事务与进程内互斥锁验证上传重放；云存储及 Redis 不连接外部服务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:infrastructure-test.properties", properties = {
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure",
        "spring.datasource.url=jdbc:h2:mem:image_upload_idempotency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORECASE=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=file:deploy/db/schema.sql,classpath:h2-datetime-precision.sql",
        "platform.security.is-log=false"
})
@Import({ImageUploadIdempotencyTest.MemorySessions.class, SettingsCacheTestConfiguration.class})
class ImageUploadIdempotencyTest {

    @TestConfiguration
    static class MemorySessions {

        @Bean
        SaTokenDao saTokenDao() {
            return new SaTokenDaoDefaultImpl();
        }
    }

    @Autowired
    TestRestTemplate testRestTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ImageFileService imageFileService;

    @Autowired
    SaTokenDao saTokenDao;

    @Autowired
    TwoLevelCache twoLevelCache;

    @MockitoBean
    RedissonClient redissonClient;

    @MockitoBean
    LettuceConnectionFactory lettuceConnectionFactory;

    @MockitoBean
    AttemptLimiter attemptLimiter;

    @MockitoBean
    OssTemplate ossTemplate;

    @MockitoSpyBean
    ImageMapper imageMapper;

    private UploadPretreatment uploadPretreatment;

    private String token;

    /**
     * 外部设施用测试替身，计费图片和流水仍经过真实 MyBatis 与数据库事务。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        SaManager.setSaTokenDao(saTokenDao);
        SettingsCacheTestConfiguration.resetCache(twoLevelCache);
        jdbcTemplate.update("DELETE FROM hub_quota_usage");
        jdbcTemplate.update("DELETE FROM hub_image");
        jdbcTemplate.update("DELETE FROM hub_ai_generation");
        jdbcTemplate.update("DELETE FROM hub_user");
        jdbcTemplate.update("UPDATE hub_settings SET config_value='100', deleted=0 WHERE config_key='upload.free-total'");
        String passwordHash = BCrypt.hashpw("Password123");
        for (long id : new long[]{1, 2}) {
            jdbcTemplate.update("INSERT INTO hub_user(id,username,email,password_hash) VALUES(?,?,?,?)",
                    id, "uploader" + id, "uploader" + id + "@example.test", passwordHash);
        }
        Map<String, String> quotaState = new ConcurrentHashMap<>();
        Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
        when(redissonClient.getBucket(anyString(), eq(StringCodec.INSTANCE))).thenAnswer(call -> {
            String key = call.getArgument(0);
            RBucket<String> rBucket = mock(RBucket.class);
            when(rBucket.get()).thenAnswer(ignored -> quotaState.get(key));
            doAnswer(write -> {
                quotaState.put(key, write.getArgument(0));
                return null;
            }).when(rBucket).set(anyString());
            return rBucket;
        });
        when(redissonClient.getLock(anyString())).thenAnswer(call -> {
            ReentrantLock mutex = locks.computeIfAbsent(call.getArgument(0), ignored -> new ReentrantLock());
            RLock rLock = mock(RLock.class);
            when(rLock.tryLock()).thenAnswer(ignored -> mutex.tryLock());
            when(rLock.isHeldByCurrentThread()).thenAnswer(ignored -> mutex.isHeldByCurrentThread());
            doAnswer(ignored -> {
                mutex.unlock();
                return null;
            }).when(rLock).unlock();
            return rLock;
        });
        FileStorageService fileStorageService = mock(FileStorageService.class);
        uploadPretreatment = mock(UploadPretreatment.class, RETURNS_SELF);
        when(ossTemplate.getFileStorageService()).thenReturn(fileStorageService);
        when(fileStorageService.of(any(MultipartFile.class))).thenReturn(uploadPretreatment);
        when(uploadPretreatment.upload()).thenReturn(stored());
        when(ossTemplate.delete(any(FileInfo.class))).thenReturn(true);
        token = testRestTemplate.postForObject("/api/app/auth/login",
                        Map.of("username", "uploader1", "password", "Password123"), JsonNode.class)
                .path("data").path("token").asText();
        assertThat(token).isNotBlank();
    }

    /**
     * 最后一个积分已消耗后，重复提交仍返回同一结果，查询接口不暴露内部指纹。
     */
    @Test
    void replayAndLookupReturnCommittedImageWithoutAnotherCharge() throws Exception {
        jdbcTemplate.update("UPDATE hub_settings SET config_value='1' WHERE config_key='upload.free-total'");
        String requestId = UUID.randomUUID().toString();
        JsonNode first = upload(requestId, png(0));
        assertThat(first.path("code").asInt()).isEqualTo(200);
        JsonNode replay = upload(requestId.toUpperCase(java.util.Locale.ROOT), png(0));
        assertThat(replay.path("code").asInt()).isEqualTo(200);
        assertThat(replay.path("data")).isEqualTo(first.path("data"));
        JsonNode receipt = getResult(requestId);
        assertThat(receipt.path("data")).isEqualTo(first.path("data"));
        assertThat(receipt.path("data").has("uploadHash")).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT upload_hash FROM hub_image", String.class)).hasSize(64);
        assertCounts(1);
        assertThat(imageFileService.quota(1L).getRemaining()).isZero();
        verify(uploadPretreatment).upload();
    }

    /**
     * 请求编号不能用于替换内容，删除后也不能复活原请求或返还积分。
     */
    @Test
    void changedPayloadAndDeletedImageCannotReuseRequest() throws Exception {
        String requestId = UUID.randomUUID().toString();
        JsonNode first = upload(requestId, png(0));
        assertThat(upload(requestId, png(0xff0000)).path("code").asInt()).isEqualTo(409);
        imageFileService.delete(1L, first.path("data").path("id").asText());
        assertThat(getResult(requestId).path("code").asInt()).isEqualTo(410);
        assertThat(upload(requestId, png(0)).path("code").asInt()).isEqualTo(410);
        assertCounts(1);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_image", Integer.class)).isEqualTo(1);
        assertThat(imageFileService.quota(1L).getUsed()).isEqualTo(1);
        verify(uploadPretreatment).upload();
    }

    /**
     * 同一编号在不同用户下独立，其他账号无法查询本人上传结果。
     */
    @Test
    void requestIdsAreScopedToOwnerAndValidated() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String firstId = imageFileService.upload(1L, file(png(0)), requestId).getId();
        assertThatThrownBy(() -> imageFileService.uploadResult(2L, requestId))
                .isInstanceOf(BizException.class).hasMessageContaining("尚未查询");
        String secondId = imageFileService.upload(2L, file(png(0)), requestId).getId();
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(imageFileService.uploadResult(1L, requestId).getId()).isEqualTo(firstId);
        assertThat(imageFileService.uploadResult(2L, requestId).getId()).isEqualTo(secondId);
        assertThat(upload("invalid", png(0)).path("code").asInt()).isEqualTo(400);
        assertThat(getResult(UUID.randomUUID().toString()).path("code").asInt()).isEqualTo(404);
        assertCounts(2);
        verify(uploadPretreatment, times(2)).upload();
    }

    /**
     * 已提交但回读失败时，查询与原请求重试都找到首次上传，不再次访问云端。
     */
    @Test
    void lostResponseCanBeConfirmedAndReplayed() throws Exception {
        String requestId = UUID.randomUUID().toString();
        doReturn(null).when(imageMapper).findOwned(eq(1L), anyString());
        assertThat(upload(requestId, png(0)).path("code").asInt()).isEqualTo(500);
        JsonNode receipt = getResult(requestId);
        assertThat(receipt.path("code").asInt()).isEqualTo(200);
        assertThat(upload(requestId, png(0)).path("data")).isEqualTo(receipt.path("data"));
        assertCounts(1);
        verify(uploadPretreatment).upload();
        verify(ossTemplate, never()).delete(any(FileInfo.class));
    }

    /**
     * 原请求尚未提交时可返回冲突或未确认，随后重试同一编号只能得到同一图片。
     */
    @Test
    void concurrentDuplicateDoesNotUploadOrChargeTwice() throws Exception {
        String requestId = UUID.randomUUID().toString();
        MockMultipartFile image = file(png(0));
        CountDownLatch uploading = new CountDownLatch(1);
        CountDownLatch finishUpload = new CountDownLatch(1);
        when(uploadPretreatment.upload()).thenAnswer(call -> {
            uploading.countDown();
            assertThat(finishUpload.await(5, TimeUnit.SECONDS)).isTrue();
            return stored();
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> imageFileService.upload(1L, image, requestId));
            assertThat(uploading.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(getResult(requestId).path("code").asInt()).isEqualTo(404);
            assertThat(upload(requestId, png(0)).path("code").asInt()).isEqualTo(409);
            finishUpload.countDown();
            String imageId = first.get(5, TimeUnit.SECONDS).getId();
            assertThat(upload(requestId, png(0)).path("data").path("id").asText()).isEqualTo(imageId);
            assertCounts(1);
            verify(uploadPretreatment).upload();
        } finally {
            finishUpload.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 未成功写入的请求可以沿用编号重试，既有无编号客户端仍保持每次独立上传。
     */
    @Test
    void failedUploadCanRetryAndLegacyUploadsRemainIndependent() throws Exception {
        String requestId = UUID.randomUUID().toString();
        when(uploadPretreatment.upload()).thenReturn(null, stored());
        assertThat(upload(requestId, png(0)).path("code").asInt()).isEqualTo(502);
        assertThat(getResult(requestId).path("code").asInt()).isEqualTo(404);
        assertCounts(0);
        assertThat(upload(requestId, png(0)).path("code").asInt()).isEqualTo(200);
        String firstLegacy = upload(null, png(0)).path("data").path("id").asText();
        String secondLegacy = upload(null, png(0)).path("data").path("id").asText();
        assertThat(firstLegacy).isNotBlank().isNotEqualTo(secondLegacy);
        assertCounts(3);
        verify(uploadPretreatment, times(4)).upload();
    }

    private void assertCounts(int count) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_image", Integer.class)).isEqualTo(count);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_quota_usage WHERE scene='IMAGE_UPLOAD'", Integer.class))
                .isEqualTo(count);
    }

    private JsonNode upload(String requestId, byte[] bytes) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "test.png";
            }
        });
        if (requestId != null) body.add("requestId", requestId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer " + token);
        return testRestTemplate.postForObject("/api/app/images", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode getResult(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        return testRestTemplate.exchange("/api/app/images/uploads/" + requestId, HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class).getBody();
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "test.png", "image/png", bytes);
    }

    private byte[] png(int rgb) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, rgb);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private FileInfo stored() {
        return new FileInfo().setPlatform("qiniu-kodo-1").setPath("images/1/")
                .setFilename("stored.png").setUrl("https://cdn.example.test/images/1/stored.png");
    }
}
