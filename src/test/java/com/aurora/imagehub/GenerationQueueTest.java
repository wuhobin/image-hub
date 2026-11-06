package com.aurora.imagehub;

import com.aurora.imagehub.cache.UploadQuotaCache;
import com.aurora.imagehub.config.aigenerate.AiImageClient;
import com.aurora.imagehub.mapper.AiGenerationMapper;
import com.aurora.imagehub.mapper.ImageMapper;
import com.aurora.imagehub.mapper.QuotaUsageMapper;
import com.aurora.imagehub.mapper.UserMapper;
import com.aurora.imagehub.service.AiModelConfigService;
import com.aurora.imagehub.service.ImageFileService;
import com.aurora.imagehub.service.QuotaUsageService;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.imagehub.service.admin.SystemSettingsService;
import com.aurora.imagehub.service.impl.AiGenerationServiceImpl;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 使用真实 Mapper SQL 和独立 H2 验证排队取消、领取竞争及积分释放，不访问外部服务。
 */
class GenerationQueueTest {

    private JdbcTemplate jdbcTemplate;

    private AiGenerationMapper aiGenerationMapper;

    private AiGenerationServiceImpl aiGenerationService;

    private UploadQuotaCache uploadQuotaCache;

    private AiImageClient aiImageClient;

    private RBucket<byte[]> referenceBucket;

    /**
     * 任务 SQL 使用真实数据库；模型、云存储和 Redis 只模拟边界，不模拟条件更新结果。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        var driverManagerDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:generation_queue_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(driverManagerDataSource);
        var resourceDatabasePopulator = new ResourceDatabasePopulator(
                new FileSystemResource("deploy/db/schema.sql"), new ClassPathResource("h2-datetime-precision.sql"));
        resourceDatabasePopulator.setSqlScriptEncoding("UTF-8");
        resourceDatabasePopulator.execute(driverManagerDataSource);
        var mybatisConfiguration = new MybatisConfiguration();
        mybatisConfiguration.setMapUnderscoreToCamelCase(true);
        mybatisConfiguration.addMapper(AiGenerationMapper.class);
        var mybatisSqlSessionFactoryBean = new MybatisSqlSessionFactoryBean();
        mybatisSqlSessionFactoryBean.setDataSource(driverManagerDataSource);
        mybatisSqlSessionFactoryBean.setConfiguration(mybatisConfiguration);
        aiGenerationMapper = new SqlSessionTemplate(mybatisSqlSessionFactoryBean.getObject())
                .getMapper(AiGenerationMapper.class);

        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock rLock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock()).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        referenceBucket = mock(RBucket.class);
        when(redissonClient.<byte[]>getBucket(anyString(), eq(ByteArrayCodec.INSTANCE))).thenReturn(referenceBucket);
        ImageMapper imageMapper = mock(ImageMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        var dataSourceTransactionManager = new DataSourceTransactionManager(driverManagerDataSource);
        uploadQuotaCache = new UploadQuotaCache(redissonClient, imageMapper, mock(QuotaUsageMapper.class),
                aiGenerationMapper, userMapper, dataSourceTransactionManager, mock(SystemSettingsService.class));
        ReflectionTestUtils.setField(uploadQuotaCache, "queueTimeoutSeconds", 900);
        ObjectProvider<PlatformTransactionManager> platformTransactionManagerProvider = mock(ObjectProvider.class);
        when(platformTransactionManagerProvider.getObject()).thenReturn(dataSourceTransactionManager);
        aiImageClient = mock(AiImageClient.class);
        aiGenerationService = new AiGenerationServiceImpl(aiGenerationMapper, mock(AiModelConfigService.class),
                mock(ImageFileService.class), imageMapper, userMapper, mock(AdminAccountService.class),
                mock(QuotaUsageService.class), uploadQuotaCache, aiImageClient,
                mock(ApplicationEventPublisher.class), redissonClient, platformTransactionManagerProvider);
        ReflectionTestUtils.setField(aiGenerationService, "baseMapper", aiGenerationMapper);
    }

    /**
     * 本人取消重复调用幂等，释放预占且清除密钥；取消后的任务不能被领取或调用模型。
     */
    @Test
    void cancellationIsIdempotentAndReleasesReservationWithoutCharge() throws Exception {
        String id = queued(1L, 0);
        assertThat(aiGenerationMapper.reserved(1L)).isEqualTo(3);

        assertThat(aiGenerationService.cancel(1L, id).getStatus()).isEqualTo("CANCELLED");
        assertThat(aiGenerationService.cancel(1L, id).getStatus()).isEqualTo("CANCELLED");

        assertThat(aiGenerationMapper.reserved(1L)).isZero();
        assertThat(aiGenerationMapper.selectById(id).getApiKeyCiphertext()).isNull();
        assertThat(aiGenerationMapper.selectById(id).getActiveUserId()).isNull();
        assertThat(aiGenerationMapper.claim(1L, id, UUID.randomUUID().toString(), 360, 900)).isZero();
        aiGenerationService.runTask(1L, id);
        verifyNoInteractions(aiImageClient);
        verify(referenceBucket).delete();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_quota_usage", Integer.class)).isZero();
        assertThat(aiGenerationMapper.deleteOwned(1L, id)).isEqualTo(1);
    }

    /**
     * 外部用户及已经开始执行的任务不能取消，不泄露其他用户任务内容或错误释放积分。
     */
    @Test
    void rejectsOtherUsersAndAlreadyClaimedTasks() {
        String id = queued(1L, 0);
        assertThatThrownBy(() -> aiGenerationService.cancel(2L, id))
                .isInstanceOf(BizException.class).hasMessage("任务不存在");
        assertThat(aiGenerationMapper.cancelQueued(2L, id)).isZero();
        assertThat(aiGenerationMapper.reserved(1L)).isEqualTo(3);

        assertThat(aiGenerationMapper.claim(1L, id, UUID.randomUUID().toString(), 360, 900)).isEqualTo(1);
        assertThatThrownBy(() -> aiGenerationService.cancel(1L, id))
                .isInstanceOf(BizException.class).hasMessageContaining("无法取消排队");
        assertThat(aiGenerationMapper.selectById(id).getStatus()).isEqualTo("GENERATING");
        assertThat(aiGenerationMapper.reserved(1L)).isEqualTo(3);
        verify(referenceBucket, never()).delete();
    }

    /**
     * 并发领取与取消仅一个条件更新成功；无论谁先提交都不会出现已取消任务继续执行。
     */
    @Test
    void cancellationAndClaimCompeteAtomically() throws Exception {
        String id = queued(1L, 0);
        try (var executorService = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var cancelled = executorService.submit(() -> {
                start.await();
                return aiGenerationMapper.cancelQueued(1L, id);
            });
            var claimed = executorService.submit(() -> {
                start.await();
                return aiGenerationMapper.claim(1L, id, UUID.randomUUID().toString(), 360, 900);
            });
            start.countDown();
            int cancellationCount = cancelled.get(5, TimeUnit.SECONDS);
            int claimCount = claimed.get(5, TimeUnit.SECONDS);
            assertThat(cancellationCount + claimCount).isEqualTo(1);
            assertThat(aiGenerationMapper.selectById(id).getStatus())
                    .isEqualTo(cancellationCount == 1 ? "CANCELLED" : "GENERATING");
            assertThat(aiGenerationMapper.reserved(1L)).isEqualTo(cancellationCount == 1 ? 0 : 3);
        }
    }

    /**
     * 调度停止时，用户访问仍释放本人超时任务；不提前释放新任务，不触碰其他账号或执行阶段。
     */
    @Test
    void userAccessExpiresOnlyOwnedWaitingTasks() {
        String expired = queued(1L, 901);
        String other = queued(2L, 901);
        String recent = queued(3L, 0);
        String generating = queued(4L, 0);
        String saving = queued(5L, 0);
        assertThat(aiGenerationMapper.claim(4L, generating, "generating-token", 360, 900)).isEqualTo(1);
        assertThat(aiGenerationMapper.claim(5L, saving, "saving-token", 360, 900)).isEqualTo(1);
        assertThat(aiGenerationMapper.beginSaving(5L, saving, "saving-token")).isEqualTo(1);
        jdbcTemplate.update("UPDATE hub_ai_generation SET create_time=TIMESTAMPADD(SECOND,-901,CURRENT_TIMESTAMP) WHERE id IN (?,?)",
                generating, saving);

        uploadQuotaCache.releaseInterruptedGeneration(1L);

        assertThat(aiGenerationMapper.selectById(expired).getStatus()).isEqualTo("FAILED");
        assertThat(aiGenerationMapper.selectById(expired).getErrorMessage()).contains("排队等待超时", "积分已释放");
        assertThat(aiGenerationMapper.reserved(1L)).isZero();
        assertThat(aiGenerationMapper.selectById(other).getStatus()).isEqualTo("QUEUED");

        aiGenerationService.expireQueued();

        assertThat(aiGenerationMapper.selectById(other).getStatus()).isEqualTo("FAILED");
        assertThat(aiGenerationMapper.selectById(recent).getStatus()).isEqualTo("QUEUED");
        assertThat(aiGenerationMapper.selectById(generating).getStatus()).isEqualTo("GENERATING");
        assertThat(aiGenerationMapper.selectById(saving).getStatus()).isEqualTo("SAVING");
        assertThat(aiGenerationMapper.reserved(2L)).isZero();
        assertThat(aiGenerationMapper.reserved(3L)).isEqualTo(3);
        assertThat(aiGenerationMapper.reserved(4L)).isEqualTo(3);
        assertThat(aiGenerationMapper.reserved(5L)).isEqualTo(3);
        assertThat(aiGenerationMapper.expireQueued(null, 900)).isZero();
    }

    /**
     * 截止时刻即禁止认领；直接执行入口同样释放排队超时任务，不调用模型。
     */
    @Test
    void expiredTaskCannotBeClaimedEvenBeforeSweep() throws Exception {
        String id = queued(1L, 900);
        assertThat(aiGenerationMapper.claim(1L, id, UUID.randomUUID().toString(), 360, 900)).isZero();

        aiGenerationService.runTask(1L, id);

        assertThat(aiGenerationMapper.selectById(id).getStatus()).isEqualTo("FAILED");
        assertThat(aiGenerationMapper.reserved(1L)).isZero();
        verifyNoInteractions(aiImageClient);
    }

    /**
     * 使用数据库时钟构造任务，避免应用时区或毫秒舍入改变截止测试结果。
     */
    private String queued(long userId, int ageSeconds) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO hub_ai_generation (id,user_id,request_id,active_user_id,model_id,points_cost,
                    model_name,model_code,base_url,images_path,api_key_ciphertext,prompt,image_size,quality,status,create_time)
                VALUES (?,?,?,?,1,3,'test-model','test','https://example.test','/images/generations','test-cipher',
                    '测试创作','1024x1024','medium','QUEUED',TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP))
                """, id, userId, UUID.randomUUID().toString(), userId, -ageSeconds);
        return id;
    }
}
