package com.aurora.imagehub.service.impl;

import com.aurora.imagehub.config.aigenerate.AiImageClient;
import com.aurora.imagehub.constants.AiGenerationConstants.TaskStatus;
import com.aurora.imagehub.constants.AiGenerationConstants.TaskStage;
import com.aurora.imagehub.config.aigenerate.AiGenerationQueuedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.aurora.imagehub.cache.UploadQuotaCache;
import com.aurora.imagehub.mapper.AiGenerationMapper;
import com.aurora.imagehub.mapper.ImageMapper;
import com.aurora.imagehub.mapper.UserMapper;
import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.imagehub.model.entity.AiModelConfig;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.model.entity.UserAccount;
import com.aurora.imagehub.model.param.GenerationParam;
import com.aurora.imagehub.model.vo.GenerationVO;
import com.aurora.imagehub.model.vo.ImageVO;
import com.aurora.imagehub.model.vo.SharedCreationVO;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.imagehub.service.*;
import com.aurora.starter.mybatisplus.mybatis.PageUtils;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持久化任务编排；网络操作在事务外，图片记录与成功终态在一个短事务内提交。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiGenerationServiceImpl extends ServiceImpl<AiGenerationMapper, AiGeneration> implements AiGenerationService {

    private final AiGenerationMapper aiGenerationMapper;

    private final AiModelConfigService aiModelConfigService;

    private final ImageFileService imageFileService;

    private final ImageMapper imageMapper;

    private final UserMapper userMapper;

    private final AdminAccountService adminAccountService;

    private final QuotaUsageService quotaUsageService;

    private final UploadQuotaCache uploadQuotaCache;

    private final AiImageClient aiImageClient;

    private final ApplicationEventPublisher applicationEventPublisher;

    private final RedissonClient redissonClient;

    private final ObjectProvider<PlatformTransactionManager> platformTransactionManagerProvider;

    @Value("${image-hub.ai.timeout-seconds:300}")
    private int timeoutSeconds;

    /**
     * 同请求编号返回原任务；数据库唯一约束覆盖多个进程之间的并发提交。
     */
    @Override
    public GenerationVO submit(long userId, GenerationParam param) {
        return submit(userId, param, null);
    }

    /**
     * 新参考图临时缓存，图库参考图校验归属后复用云地址；同账号串行且重试幂等。
     */
    @Override
    public GenerationVO submit(long userId, GenerationParam param, MultipartFile reference) {
        if (reference != null && param.getReferenceImageId() != null) throw new BizException(400, "只能选择一张参考图");
        AiGeneration previous = byRequest(userId, param.getRequestId());
        if (previous != null) return task(userId, previous.getId());
        AiModelConfig model = aiModelConfigService.requireEnabled(param.getModelId());
        GenerationVO submitted = uploadQuotaCache.reserveGeneration(userId, model.getPointsCost(), () -> {
            AiGeneration existing = byRequest(userId, param.getRequestId());
            return existing == null ? null : response(existing);
        }, () -> {
            if (!List.of(model.getSizes().split(",")).contains(param.getSize())
                    || !List.of(model.getQualities().split(",")).contains(param.getQuality())) {
                throw new BizException(400, "所选尺寸或质量不属于该模型，请刷新选项");
            }
            if (activeEntity(userId) != null) throw new BizException(409, "已有未完成的创作，请先完成或放弃保存");
            AiGeneration task = new AiGeneration();
            task.setId(UUID.randomUUID().toString());
            task.setUserId(userId);
            task.setActiveUserId(userId);
            task.setRequestId(param.getRequestId());
            task.setModelId(model.getId());
            task.setModelName(model.getName());
            task.setModelCode(model.getModelCode());
            task.setPointsCost(model.getPointsCost());
            task.setBaseUrl(model.getBaseUrl());
            String imagesPath = model.getImagesPath();
            if (reference != null || param.getReferenceImageId() != null) {
                if (!imagesPath.endsWith("/generations") && !imagesPath.endsWith("/edits")) {
                    throw new BizException(400, "参考图需要模型接口路径以 /generations 或 /edits 结尾");
                }
                imagesPath = imagesPath.replaceFirst("/generations$", "/edits");
            }
            if (param.getReferenceImageId() != null) {
                // 只接受本人未删除的图片 ID，云地址取自服务端记录，避免客户端指定任意 URL。
                ImageFile source = imageMapper.findOwned(userId, param.getReferenceImageId());
                if (source == null) throw new BizException(404, "参考图不存在或已删除");
                if (source.getSize() > 10 * 1024 * 1024) throw new BizException(413, "参考图不能超过 10 MB");
                if (!List.of("JPG", "PNG", "WEBP").contains(source.getType())) {
                    throw new BizException(400, "参考图仅支持 JPG、PNG、WEBP 格式");
                }
                task.setReferenceImageSource(source.getUrl());
            }
            task.setImagesPath(imagesPath);
            task.setApiKeyCiphertext(model.getApiKeyCiphertext());
            task.setPrompt(param.getPrompt().trim());
            task.setImageSize(param.getSize());
            task.setQuality(param.getQuality());
            task.setStatus(TaskStatus.QUEUED.name());
            boolean cachedReference = false;
            try {
                if (reference != null) {
                    String mime = imageFileService.validateReference(reference);
                    task.setReferenceImageSource("redis:" + mime);
                    byte[] referenceBytes;
                    try {
                        referenceBytes = reference.getBytes();
                    } catch (java.io.IOException e) {
                        throw new BizException(400, "参考图读取失败，请重新选择图片", e);
                    }
                    // 参考图从提交起最多缓存 10 分钟；排队超时明确失败，工作线程已读取后不受过期影响。
                    cachedReference = true;
                    referenceBucket(task).set(referenceBytes, java.time.Duration.ofMinutes(10));
                }
                uploadQuotaCache.checkOwnership(userId);
                aiGenerationMapper.insert(task);
            } catch (RuntimeException e) {
                // 写库结果不明时先查本任务；无法确认则交给 TTL，不能删掉已接收任务的参考图。
                if (cachedReference) {
                    try {
                        AiGeneration saved = byRequest(userId, param.getRequestId());
                        if (saved == null || !task.getId().equals(saved.getId())) discardReference(task);
                    } catch (RuntimeException verification) {
                        log.info("参考图入库状态无法确认，等待缓存过期：taskId={}", task.getId(), verification);
                    }
                }
                if (e instanceof DuplicateKeyException) {
                    AiGeneration duplicate = byRequest(userId, param.getRequestId());
                    if (duplicate != null) return response(duplicate);
                    throw new BizException(409, "已有未完成的创作，请刷新任务");
                }
                throw e;
            }
            return task(userId, task.getId());
        });
        // 持久化和积分预占均已完成，通知失败不能把已接收的请求变成提交失败。
        if (TaskStatus.QUEUED.name().equals(submitted.getStatus())) {
            try {
                applicationEventPublisher.publishEvent(new AiGenerationQueuedEvent());
            } catch (RuntimeException e) {
                log.info("AI 创作即时调度失败，等待轮询重试：taskId={}", submitted.getId(), e);
            }
        }
        return submitted;
    }

    /**
     * 所有用户查询显式限定归属，避免通过任务编号读取他人提示词或图片。
     */
    @Override
    public GenerationVO task(long userId, String id) {
        uploadQuotaCache.releaseInterruptedGeneration(userId);
        return response(requireOwned(userId, id));
    }

    @Override
    public GenerationVO active(long userId) {
        uploadQuotaCache.releaseInterruptedGeneration(userId);
        AiGeneration task = activeEntity(userId);
        return task == null ? null : response(task);
    }

    @Override
    public Page<GenerationVO> history(long userId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50) throw new BizException(400, "分页参数无效");
        uploadQuotaCache.releaseInterruptedGeneration(userId);
        Page<AiGeneration> tasks = page(PageUtils.buildPage(page, pageSize), Wrappers.<AiGeneration>lambdaQuery()
                .eq(AiGeneration::getUserId, userId).orderByDesc(AiGeneration::getCreateTime, AiGeneration::getId));
        var imageIds = tasks.getRecords().stream().filter(task -> TaskStatus.SUCCEEDED.name().equals(task.getStatus()))
                .map(AiGeneration::getId).toList();
        var images = new java.util.HashMap<String, ImageVO>();
        if (!imageIds.isEmpty()) {
            // 仅批量读取当前页的本人图片；逻辑删除由 MyBatis-Plus 过滤，删除后历史仍保留。
            imageMapper.selectList(Wrappers.<ImageFile>lambdaQuery().eq(ImageFile::getUserId, userId)
                    .in(ImageFile::getId, imageIds)).forEach(image -> images.put(image.getId(), ImageVO.from(image)));
        }
        return PageUtils.convert(tasks, task -> GenerationVO.from(task, images.get(task.getId())));
    }

    /**
     * 兼容旧客户端路径；结果不再暂存，校验归属后明确拒绝重试，不重新调用模型。
     */
    @Override
    public GenerationVO retrySave(long userId, String id) {
        requireOwned(userId, id);
        throw new BizException(409, "生成结果不再暂存，请重新提交创作");
    }

    /**
     * 发布资格和下架状态由数据库条件更新兜底，不能依赖前端隐藏按钮。
     */
    @Override
    public GenerationVO share(long userId, String id, boolean promptPublic) {
        AiGeneration task = requireOwned(userId, id);
        if ("BLOCKED".equals(task.getShareStatus())) throw new BizException(403, "作品已被管理员下架，不能重新发布");
        if (aiGenerationMapper.share(userId, id, UUID.randomUUID().toString(), promptPublic) != 1) {
            throw new BizException(409, "仅可分享生成成功且未删除、未下架的 AI 作品，请刷新后重试");
        }
        return response(requireOwned(userId, id));
    }

    /**
     * 撤销为幂等操作；作品和积分不变，管理员下架标记不得被作者清除。
     */
    @Override
    public void revokeShare(long userId, String id) {
        requireOwned(userId, id);
        aiGenerationMapper.revokeShare(userId, id);
    }

    /**
     * 公开接口每次查库，不缓存可撤销的分享内容。
     */
    @Override
    public Page<SharedCreationVO> publicCreations(int page, int pageSize) {
        return sharedCreations(page, pageSize, null, false);
    }

    /**
     * 缺失和不可见作品统一返回 404，避免枚举私有任务。
     */
    @Override
    public SharedCreationVO publicCreation(String shareId) {
        var result = sharedCreations(1, 1, shareId, false);
        if (result.getRecords().isEmpty()) throw new BizException(404, "作品不存在或已停止分享");
        return result.getRecords().getFirst();
    }

    /**
     * 管理员必须仍然有效，普通用户 Token 不能用于管理分享。
     */
    @Override
    public Page<SharedCreationVO> managedCreations(int page, int pageSize) {
        adminAccountService.currentAdmin();
        return sharedCreations(page, pageSize, null, true);
    }

    /**
     * 下架保留作者原作品，更新状态后后续公开查询立即不可见。
     */
    @Override
    public void blockShare(String shareId) {
        var admin = adminAccountService.currentAdmin();
        if (aiGenerationMapper.blockShare(shareId) != 1) throw new BizException(404, "分享不存在或已撤销");
        log.info("管理员下架作品：adminId={}, shareId={}", admin.getId(), shareId);
    }

    /**
     * 批量读取当前页图片和作者，转换白名单 VO；不复用含任务信息的本人详情响应。
     */
    private Page<SharedCreationVO> sharedCreations(int page, int pageSize, String shareId, boolean includeBlocked) {
        if (page < 1 || pageSize < 1 || pageSize > 50) throw new BizException(400, "分页参数无效");
        Page<AiGeneration> tasks = aiGenerationMapper.shared(PageUtils.buildPage(page, pageSize), shareId, includeBlocked);
        if (tasks.getRecords().isEmpty()) return PageUtils.convert(tasks, task -> new SharedCreationVO());
        var ids = tasks.getRecords().stream().map(AiGeneration::getId).toList();
        var userIds = tasks.getRecords().stream().map(AiGeneration::getUserId).distinct().toList();
        var images = imageMapper.selectList(Wrappers.<ImageFile>lambdaQuery()
                        .select(ImageFile::getId, ImageFile::getUserId, ImageFile::getUrl, ImageFile::getWidth, ImageFile::getHeight)
                        .in(ImageFile::getId, ids).in(ImageFile::getUserId, userIds))
                .stream().collect(java.util.stream.Collectors.toMap(ImageFile::getId, image -> image));
        var authors = userMapper.selectList(Wrappers.<UserAccount>lambdaQuery()
                        .select(UserAccount::getId, UserAccount::getUsername).in(UserAccount::getId, userIds))
                .stream().collect(java.util.stream.Collectors.toMap(UserAccount::getId, UserAccount::getUsername));
        // 读取期间发生原图或作者删除时，也不返回失效作品；下一次分页会使用最新总数。
        tasks.setRecords(tasks.getRecords().stream().filter(task -> images.containsKey(task.getId())
                && task.getUserId().equals(images.get(task.getId()).getUserId()) && authors.containsKey(task.getUserId())).toList());
        return PageUtils.convert(tasks, task -> {
            ImageFile image = images.get(task.getId());
            SharedCreationVO result = new SharedCreationVO();
            result.setShareId(task.getShareId());
            result.setShareStatus(task.getShareStatus());
            result.setAuthorName(authors.get(task.getUserId()));
            result.setImageUrl(image.getUrl());
            result.setWidth(image.getWidth());
            result.setHeight(image.getHeight());
            result.setModelId(task.getModelId());
            result.setModelName(task.getModelName());
            result.setSize(task.getImageSize());
            result.setQuality(task.getQuality());
            result.setPromptPublic(task.getPromptPublic());
            result.setPrompt(Boolean.TRUE.equals(task.getPromptPublic()) ? task.getPrompt() : null);
            result.setPublishedTime(task.getPublishedTime());
            return result;
        });
    }

    /**
     * 兼容旧客户端路径；当前任务不再存在可放弃的暂存结果。
     */
    @Override
    public void abandon(long userId, String id) {
        requireOwned(userId, id);
        throw new BizException(409, "没有可放弃的暂存结果");
    }

    /**
     * 一次领取完成生成与上传；图片字节仅存在于当前调用，不入库、不自动重新生成。
     */
    @Override
    public void runTask(long userId, String id) {
        withTaskLock(id, () -> {
            AiGeneration task = requireOwned(userId, id);
            if (!TaskStatus.QUEUED.name().equals(task.getStatus()) || task.getWorkToken() != null) return null;
            String token = UUID.randomUUID().toString();
            if (aiGenerationMapper.claim(userId, id, token, timeoutSeconds + 60) != 1) return null;
            // 认领后使用单调时钟计时，不受排队、系统校时和后续审计时间更新影响。
            long started = System.nanoTime();
            if (task.getCreateTime() != null) {
                // 创建时间为数据库秒精度，跨数据库与应用时钟计算，仅用于近似排队耗时诊断。
                log.info("AI 创作耗时：taskId={}, 阶段={}, 近似耗时={} 秒",
                        id, TaskStage.QUEUE_WAIT.getDescription(), Math.max(0, System.currentTimeMillis() - task.getCreateTime().getTime()) / 1000.0);
            }
            TaskStage stage = TaskStage.MODEL_REQUEST;
            try {
                boolean cachedReference = task.getReferenceImageSource() != null && task.getReferenceImageSource().startsWith("redis:");
                byte[] bytes;
                try {
                    if (cachedReference) {
                        byte[] referenceBytes = referenceBucket(task).get();
                        if (referenceBytes == null)
                            throw new BizException(410, "参考图已过期或不可用，请重新选择图片并提交");
                        // Redis 仅存原始字节；发送前才编码，不把图片内容更新到数据库。
                        task.setReferenceImageSource("data:" + task.getReferenceImageSource().substring(6)
                                + ";base64," + java.util.Base64.getEncoder().encodeToString(referenceBytes));
                    }
                    bytes = aiImageClient.generate(task);
                } finally {
                    if (cachedReference) discardReference(task);
                }
                stage = TaskStage.SAVE_IMAGE;
                requireTaskLock(id);
                long savingStarted = System.nanoTime();
                if (aiGenerationMapper.beginSaving(userId, id, token) != 1) {
                    throw new BizException(409, "生成任务执行期限已过，请重新提交");
                }
                saveResult(task, token, bytes, savingStarted, started);
            } catch (Exception e) {
                long durationMillis = (System.nanoTime() - started) / 1_000_000;
                if (stage != TaskStage.MODEL_REQUEST) {
                    log.info("AI 创作处理失败：taskId={}, 阶段={}", id, stage.getDescription(), e);
                }
                String message = e instanceof BizException ? e.getMessage()
                        : stage == TaskStage.MODEL_REQUEST ? "生成未取得结果，积分已释放，请重新提交"
                          : "图片保存失败，积分已释放，请重新提交创作";
                // 先确认任务未成功并记录失败；提交结果不明时不得删除可能已入库的云文件。
                requireTaskLock(id);
                int updated = aiGenerationMapper.generationFailed(userId, id, token, message, durationMillis);
                log.info("AI 创作失败已记录：taskId={}, 预留积分已释放={}", id, updated == 1);
                if (updated == 1) {
                    try {
                        cleanupPendingUpload(requireOwned(userId, id));
                    } catch (Exception cleanup) {
                        log.info("AI 创作云文件清理失败，需要人工处理：taskId={}", id,
                                cleanup);
                    }
                }
            } finally {
                log.info("AI 创作耗时：taskId={}, 阶段={}, 耗时={} 秒",
                        id, TaskStage.TOTAL.getDescription(), (System.nanoTime() - started) / 1_000_000 / 1000.0);
            }
            return null;
        });
    }

    /**
     * 直接上传当前内存结果，云请求在事务外；实测耗时与图片记录、成功状态在同一短事务提交。
     */
    private void saveResult(AiGeneration task, String token, byte[] bytes, long savingStarted, long taskStarted) {
        ImageFile image = imageFileService.storeGenerated(task.getUserId(), task.getId(), bytes, storageInfo -> {
            requireTaskLock(task.getId());
            if (aiGenerationMapper.prepareUpload(task.getUserId(), task.getId(), token, storageInfo) != 1) {
                throw new BizException(409, "任务执行期限已过或执行权已失效");
            }
        });
        requireTaskLock(task.getId());
        long started = System.nanoTime();
        try {
            // 保存期限为 120 秒，扣除校验和上传时间并预留 5 秒给提交；实际提交仍校验数据库期限。
            long waitMillis = Math.max(0, 115_000 - (System.nanoTime() - savingStarted) / 1_000_000);
            uploadQuotaCache.completeGeneration(task.getUserId(), waitMillis, () -> {
                TransactionTemplate transaction = new TransactionTemplate(platformTransactionManagerProvider.getObject());
                return transaction.execute(status -> {
                    requireTaskLock(task.getId());
                    uploadQuotaCache.checkOwnership(task.getUserId());
                    if (aiGenerationMapper.succeed(task.getUserId(), task.getId(), token,
                            (System.nanoTime() - taskStarted) / 1_000_000) != 1) {
                        throw new BizException(409, "任务执行期限已过，不能保存结果");
                    }
                    image.setPointsCost(task.getPointsCost());
                    imageMapper.insert(image);
                    quotaUsageService.recordConsumption(image);
                    return null;
                });
            });
        } finally {
            log.info("AI 创作耗时：taskId={}, 阶段={}, 耗时={} 秒",
                    task.getId(), TaskStage.PERSIST_RESULT.getDescription(), (System.nanoTime() - started) / 1_000_000 / 1000.0);
        }
    }

    /**
     * 失败时仅即时补偿一次；删除失败保留文件定位供人工处理，不再定时扫描重试。
     */
    private void cleanupPendingUpload(AiGeneration task) {
        if (task.getPendingStorageInfo() == null || task.getWorkToken() != null) return;
        requireTaskLock(task.getId());
        imageFileService.discardGenerated(task.getPendingStorageInfo());
        requireTaskLock(task.getId());
        aiGenerationMapper.clearPendingUpload(task.getUserId(), task.getId());
    }

    /**
     * 缓存定位只由可信任务归属和服务端 UUID 生成，不能用客户端指定的键访问缓存。
     */
    private org.redisson.api.RBucket<byte[]> referenceBucket(AiGeneration task) {
        return redissonClient.getBucket("image-hub:reference:{" + task.getUserId() + "}:" + task.getId(),
                org.redisson.client.codec.ByteArrayCodec.INSTANCE);
    }

    /**
     * 模型调用结束即清理；Redis 暂不可用时由 TTL 兜底，不改变已经取得的生成结果。
     */
    private void discardReference(AiGeneration task) {
        try {
            referenceBucket(task).delete();
        } catch (RuntimeException e) {
            log.info("参考图缓存清理失败，等待自动过期：taskId={}", task.getId(), e);
        }
    }

    private AiGeneration byRequest(long userId, String requestId) {
        return getOne(Wrappers.<AiGeneration>lambdaQuery().eq(AiGeneration::getUserId, userId).eq(AiGeneration::getRequestId, requestId));
    }

    private AiGeneration activeEntity(long userId) {
        return getOne(Wrappers.<AiGeneration>lambdaQuery().eq(AiGeneration::getUserId, userId).eq(AiGeneration::getActiveUserId, userId));
    }

    private AiGeneration requireOwned(long userId, String id) {
        AiGeneration task = getOne(Wrappers.<AiGeneration>lambdaQuery().eq(AiGeneration::getUserId, userId).eq(AiGeneration::getId, id));
        if (task == null) throw new BizException(404, "任务不存在");
        return task;
    }

    private GenerationVO response(AiGeneration task) {
        ImageFile image = TaskStatus.SUCCEEDED.name().equals(task.getStatus()) ? imageMapper.findOwned(task.getUserId(), task.getId()) : null;
        return GenerationVO.from(task, image == null ? null : ImageVO.from(image));
    }

    /**
     * 使用任务独立锁；释放失败交给Redisson看门狗过期，不覆盖业务结果。
     */
    private <T> T withTaskLock(String id, Supplier<T> operation) {
        var lock = redissonClient.getLock("image-hub:generation:" + id);
        if (!lock.tryLock()) throw new BizException(409, "任务正在处理，请稍后刷新");
        try {
            return operation.get();
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            } catch (RuntimeException e) {
                log.warn("AI 创作任务锁释放失败，等待租期过期：taskId={}", id);
            }
        }
    }

    private void requireTaskLock(String id) {
        if (!redissonClient.getLock("image-hub:generation:" + id).isHeldByCurrentThread()) {
            throw new BizException(503, "任务执行权已失效");
        }
    }
}
