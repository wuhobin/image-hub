package com.aurora.imagehub;

import com.aurora.imagehub.cache.UploadQuotaCache;
import com.aurora.imagehub.config.aigenerate.AiImageLimits;
import com.aurora.imagehub.config.aigenerate.AiImageClient;
import com.aurora.imagehub.mapper.AiGenerationMapper;
import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.imagehub.model.entity.AiModelConfig;
import com.aurora.imagehub.model.param.GenerationParam;
import com.aurora.imagehub.service.AiModelConfigService;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.imagehub.service.impl.AiGenerationServiceImpl;
import com.aurora.imagehub.mapper.ImageMapper;
import com.aurora.imagehub.mapper.UserMapper;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.model.entity.UserAccount;
import com.aurora.imagehub.ratelimit.AttemptLimiter;
import com.aurora.imagehub.service.ImageFileService;
import com.aurora.imagehub.service.QuotaUsageService;
import com.aurora.imagehub.service.UserInvitationService;
import com.aurora.imagehub.service.impl.ImageFileServiceImpl;
import com.aurora.imagehub.service.impl.UserAccountServiceImpl;
import com.aurora.starter.oss.template.OssTemplate;
import com.aurora.starter.oss.validation.FileUploadValidator;
import com.aurora.starter.verification.mail.MailVerificationService;
import com.aurora.starter.webmvc.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import javax.imageio.ImageIO;

import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.upload.UploadPretreatment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.redisson.api.RLock;
import org.redisson.api.RBucket;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 使用真实 Spring 事务同步验证云文件补偿，仅模拟存储和数据库操作，不连接外部资源。
 */
class ImageTransactionOutcomeTest {

    /**
     * 提交结果不明或事务未开始时保留已上传对象；只有确认回滚才允许删除。
     */
    @ParameterizedTest
    @EnumSource(Outcome.class)
    void uploadCompensatesOnlyConfirmedRollback(Outcome outcome) throws Exception {
        ImageMapper imageMapper = mock(ImageMapper.class);
        OssTemplate ossTemplate = mock(OssTemplate.class);
        FileUploadValidator fileUploadValidator = mock(FileUploadValidator.class);
        UploadQuotaCache uploadQuotaCache = mock(UploadQuotaCache.class);
        QuotaUsageService quotaUsageService = mock(QuotaUsageService.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        UploadPretreatment uploadPretreatment = mock(UploadPretreatment.class, RETURNS_SELF);
        var file = png();
        when(fileUploadValidator.validate(file)).thenReturn("image/png");
        when(ossTemplate.getFileStorageService()).thenReturn(fileStorageService);
        when(fileStorageService.of(file)).thenReturn(uploadPretreatment);
        when(uploadPretreatment.upload()).thenReturn(new FileInfo().setPlatform("qiniu-kodo-1")
                .setPath("images/1/").setFilename("stored.png").setUrl("https://cdn.example.test/stored.png"));
        when(ossTemplate.delete(any(FileInfo.class))).thenReturn(true);
        when(uploadQuotaCache.consume(eq(1L), anyString(), any())).thenAnswer(call ->
                call.<LongFunction<?>>getArgument(2).apply(100L));
        var completionStatus = new AtomicInteger(-1);
        var inserted = observeInsert(imageMapper, outcome, completionStatus);
        when(imageMapper.findOwned(eq(1L), anyString())).thenAnswer(call ->
                outcome == Outcome.COMMITTED ? inserted.get() : null);
        var imageFileService = new ImageFileServiceImpl(imageMapper, ossTemplate, fileUploadValidator,
                new AiImageLimits("10MB"), new ObjectMapper(), uploadQuotaCache, quotaUsageService,
                new OutcomeTransactionManager(outcome));

        if (outcome == Outcome.COMMITTED) {
            assertThat(imageFileService.upload(1L, file).getUrl()).isEqualTo("https://cdn.example.test/stored.png");
        } else {
            assertThatThrownBy(() -> imageFileService.upload(1L, file)).isInstanceOf(BizException.class);
        }

        assertThat(completionStatus.get()).isEqualTo(outcome.completionStatus);
        verify(uploadPretreatment).upload();
        verify(ossTemplate, times(outcome == Outcome.ROLLED_BACK ? 1 : 0)).delete(any(FileInfo.class));
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    /**
     * 头像引用与图片记录的提交结果使用相同补偿边界，不靠可能延迟的回读判断回滚。
     */
    @ParameterizedTest
    @EnumSource(Outcome.class)
    @SuppressWarnings("unchecked")
    void avatarCompensatesOnlyConfirmedRollback(Outcome outcome) throws Exception {
        UserMapper userMapper = mock(UserMapper.class);
        ImageMapper imageMapper = mock(ImageMapper.class);
        ImageFileService imageFileService = mock(ImageFileService.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock rLock = mock(RLock.class);
        ObjectProvider<MailVerificationService> mailVerificationServiceProvider = mock(ObjectProvider.class);
        var user = new UserAccount();
        user.setId(1L);
        user.setUsername("avatar-user");
        user.setEmail("avatar@example.test");
        var uploaded = new ImageFile();
        uploaded.setId("new-avatar");
        uploaded.setUserId(1L);
        uploaded.setSourceType("AVATAR");
        uploaded.setUrl("https://cdn.example.test/avatar.png");
        var file = png();
        when(userMapper.findById(1L)).thenReturn(user);
        when(userMapper.lockById(1L)).thenReturn(1L);
        when(imageMapper.unusedAvatars(1L)).thenReturn(List.of());
        when(imageFileService.storeAvatar(1L, file)).thenReturn(uploaded);
        when(redissonClient.getLock("image-hub:avatar:1")).thenReturn(rLock);
        when(rLock.tryLock()).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(userMapper.updateAvatar(1L, uploaded.getId())).thenAnswer(call -> {
            user.setAvatarImageId(uploaded.getId());
            return 1;
        });
        var completionStatus = new AtomicInteger(-1);
        observeInsert(imageMapper, outcome, completionStatus);
        when(imageMapper.findOwned(1L, uploaded.getId())).thenReturn(outcome == Outcome.COMMITTED ? uploaded : null);
        var userAccountService = new UserAccountServiceImpl(userMapper, mailVerificationServiceProvider,
                mock(AttemptLimiter.class), mock(UploadQuotaCache.class), mock(UserInvitationService.class),
                imageMapper, imageFileService, redissonClient, new OutcomeTransactionManager(outcome));

        if (outcome == Outcome.COMMITTED) {
            assertThat(userAccountService.uploadAvatar(1L, file).getAvatarUrl()).startsWith(uploaded.getUrl());
        } else {
            assertThatThrownBy(() -> userAccountService.uploadAvatar(1L, file)).isInstanceOf(RuntimeException.class);
        }

        assertThat(completionStatus.get()).isEqualTo(outcome.completionStatus);
        verify(imageFileService).storeAvatar(1L, file);
        verify(imageFileService, times(outcome == Outcome.ROLLED_BACK ? 1 : 0)).discardUncommitted(uploaded);
        verify(rLock).unlock();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    /**
     * 参考图先写缓存；任务提交未知时不能用暂时查不到记录作为删除依据。
     */
    @ParameterizedTest
    @EnumSource(Outcome.class)
    @SuppressWarnings("unchecked")
    void generationReferenceIsDiscardedOnlyAfterConfirmedRollback(Outcome outcome) throws Exception {
        AiGenerationMapper aiGenerationMapper = mock(AiGenerationMapper.class);
        AiModelConfigService aiModelConfigService = mock(AiModelConfigService.class);
        ImageFileService imageFileService = mock(ImageFileService.class);
        UploadQuotaCache uploadQuotaCache = mock(UploadQuotaCache.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBucket<byte[]> rBucket = mock(RBucket.class);
        ObjectProvider<PlatformTransactionManager> platformTransactionManagerProvider = mock(ObjectProvider.class);
        when(platformTransactionManagerProvider.getObject()).thenReturn(new OutcomeTransactionManager(outcome));
        when(redissonClient.<byte[]>getBucket(anyString(), eq(org.redisson.client.codec.ByteArrayCodec.INSTANCE)))
                .thenReturn(rBucket);
        when(uploadQuotaCache.reserveGeneration(eq(1L), eq(1), any(), any())).thenAnswer(call ->
                call.<LongFunction<?>>getArgument(3).apply(100L));
        var model = new AiModelConfig();
        model.setId(1L);
        model.setName("test-model");
        model.setModelCode("gpt-image-2");
        model.setBaseUrl("https://example.test");
        model.setImagesPath("/v1/images/generations");
        model.setSizes("1024x1024");
        model.setQualities("medium");
        model.setPointsCost(1);
        when(aiModelConfigService.requireEnabled(1L)).thenReturn(model);
        var reference = png();
        when(imageFileService.validateReference(reference)).thenReturn("image/png");
        var completionStatus = new AtomicInteger(-1);
        var inserted = new AtomicReference<AiGeneration>();
        when(aiGenerationMapper.insert(any(AiGeneration.class))).thenAnswer(call -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completionStatus.set(status);
                }
            });
            if (outcome == Outcome.ROLLED_BACK) throw new DataIntegrityViolationException("insert rejected");
            inserted.set(call.getArgument(0));
            return 1;
        });
        var aiGenerationService = spy(new AiGenerationServiceImpl(aiGenerationMapper, aiModelConfigService,
                imageFileService, mock(ImageMapper.class), mock(UserMapper.class), mock(AdminAccountService.class),
                mock(QuotaUsageService.class), uploadQuotaCache, mock(AiImageClient.class),
                mock(ApplicationEventPublisher.class), redissonClient, platformTransactionManagerProvider));
        doAnswer(call -> inserted.get()).when(aiGenerationService).getOne(any());
        var param = new GenerationParam();
        param.setRequestId("00000000-0000-0000-0000-000000000001");
        param.setModelId(1L);
        param.setPrompt("test");
        param.setSize("1024x1024");
        param.setQuality("medium");

        if (outcome == Outcome.COMMITTED) {
            assertThat(aiGenerationService.submit(1L, param, reference).getStatus()).isEqualTo("QUEUED");
        } else {
            assertThatThrownBy(() -> aiGenerationService.submit(1L, param, reference)).isInstanceOf(RuntimeException.class);
        }

        assertThat(completionStatus.get()).isEqualTo(outcome.completionStatus);
        verify(rBucket).set(any(byte[].class), eq(java.time.Duration.ofSeconds(960)));
        verify(rBucket, times(outcome == Outcome.ROLLED_BACK ? 1 : 0)).delete();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    /**
     * 观察框架实际派发的完成状态；写入异常触发真实 rollback 流程，不直接调用生产回调。
     */
    private AtomicReference<ImageFile> observeInsert(ImageMapper imageMapper, Outcome outcome,
                                                     AtomicInteger completionStatus) {
        var inserted = new AtomicReference<ImageFile>();
        when(imageMapper.insert(any(ImageFile.class))).thenAnswer(call -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completionStatus.set(status);
                }
            });
            if (outcome == Outcome.ROLLED_BACK) throw new DataIntegrityViolationException("insert rejected");
            inserted.set(call.getArgument(0));
            return 1;
        });
        return inserted;
    }

    /**
     * 使用真实小图片经过上传入口，避免绕开上传和事务之间的顺序。
     */
    private MockMultipartFile png() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("file", "test.png", "image/png", bytes.toByteArray());
    }

    private enum Outcome {
        COMMITTED(TransactionSynchronization.STATUS_COMMITTED),
        ROLLED_BACK(TransactionSynchronization.STATUS_ROLLED_BACK),
        UNKNOWN(TransactionSynchronization.STATUS_UNKNOWN),
        NOT_STARTED(-1);

        private final int completionStatus;

        Outcome(int completionStatus) {
            this.completionStatus = completionStatus;
        }
    }

    /**
     * 只注入底层 begin/commit 故障，提交、回滚及同步回调全部交给 Spring 实现。
     */
    private static final class OutcomeTransactionManager extends AbstractPlatformTransactionManager {

        private final Outcome outcome;

        private OutcomeTransactionManager(Outcome outcome) {
            this.outcome = outcome;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (outcome == Outcome.NOT_STARTED) throw new CannotCreateTransactionException("begin unavailable");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (outcome == Outcome.UNKNOWN) throw new TransactionSystemException("commit acknowledgement lost");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
