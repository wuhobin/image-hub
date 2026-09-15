package com.aurora.imagehub.service.impl;

import com.aurora.imagehub.config.aigenerate.AiImageClient;
import com.aurora.imagehub.cache.UploadQuotaCache;
import com.aurora.imagehub.mapper.AiGenerationMapper;
import com.aurora.imagehub.mapper.ImageMapper;
import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.model.param.GenerationParam;
import com.aurora.imagehub.model.vo.GenerationVO;
import com.aurora.imagehub.model.vo.ImageVO;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久化任务编排；网络操作在事务外，图片记录与成功终态在一个短事务内提交。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiGenerationServiceImpl extends ServiceImpl<AiGenerationMapper, AiGeneration> implements AiGenerationService {

    private final AiGenerationMapper aiGenerationMapper;

    private final AiModelConfigService aiModelConfigService;

    private final ImageFileService imageFileService;

    private final ImageMapper imageMapper;

    private final UploadQuotaCache uploadQuotaCache;

    private final AiImageClient aiImageClient;

    private final RedissonClient redissonClient;

    private final ObjectProvider<PlatformTransactionManager> platformTransactionManagerProvider;

    @Value("${image-hub.ai.timeout-seconds:180}")
    private int timeoutSeconds;

    /** 同请求编号返回原任务；数据库唯一约束覆盖多个进程之间的并发提交。 */
    @Override
    public GenerationVO submit(long userId, GenerationParam param) {
        AiGeneration previous = byRequest(userId, param.getRequestId());
        if (previous != null) return response(previous);
        var model = aiModelConfigService.requireEnabled(param.getModelId());
        if (!List.of(model.getSizes().split(",")).contains(param.getSize())
                || !List.of(model.getQualities().split(",")).contains(param.getQuality())) {
            throw new BizException(400, "所选尺寸或质量不属于该模型，请刷新选项");
        }
        return uploadQuotaCache.reserveGeneration(userId, () -> {
            AiGeneration existing = byRequest(userId, param.getRequestId());
            if (existing != null) return response(existing);
            if (activeEntity(userId) != null) throw new BizException(409, "已有未完成的创作，请先完成或放弃保存");
            AiGeneration task = new AiGeneration();
            task.setId(UUID.randomUUID().toString());
            task.setUserId(userId);
            task.setActiveUserId(userId);
            task.setRequestId(param.getRequestId());
            task.setModelId(model.getId());
            task.setModelName(model.getName());
            task.setModelCode(model.getModelCode());
            task.setBaseUrl(model.getBaseUrl());
            task.setImagesPath(model.getImagesPath());
            task.setApiKeyCiphertext(model.getApiKeyCiphertext());
            task.setPrompt(param.getPrompt().trim());
            task.setImageSize(param.getSize());
            task.setQuality(param.getQuality());
            task.setStatus("QUEUED");
            try {
                uploadQuotaCache.checkOwnership(userId);
                aiGenerationMapper.insert(task);
            } catch (DuplicateKeyException e) {
                AiGeneration duplicate = byRequest(userId, param.getRequestId());
                if (duplicate != null) return response(duplicate);
                throw new BizException(409, "已有未完成的创作，请刷新任务");
            }
            return task(userId, task.getId());
        });
    }

    /** 所有用户查询显式限定归属，避免通过任务编号读取他人提示词或图片。 */
    @Override
    public GenerationVO task(long userId, String id) {
        return response(requireOwned(userId, id));
    }

    @Override
    public GenerationVO active(long userId) {
        AiGeneration task = activeEntity(userId);
        return task == null ? null : response(task);
    }

    @Override
    public Page<GenerationVO> history(long userId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50) throw new BizException(400, "分页参数无效");
        return PageUtils.convert(page(PageUtils.buildPage(page, pageSize), Wrappers.<AiGeneration>lambdaQuery()
                .eq(AiGeneration::getUserId, userId).orderByDesc(AiGeneration::getCreateTime, AiGeneration::getId)), this::response);
    }

    /** 只重新进入保存阶段，不回到排队生图；条件更新阻止过期或重复重试。 */
    @Override
    public GenerationVO retrySave(long userId, String id) {
        return withTaskLock(id, () -> {
            requireOwned(userId, id);
            if (aiGenerationMapper.retrySave(userId, id) != 1) throw new BizException(409, "任务不可重试或图片已过期，请刷新");
            return task(userId, id);
        });
    }

    /** 仅允许放弃等待重试的图片；运行中的模型调用不提供伪取消。 */
    @Override
    public void abandon(long userId, String id) {
        withTaskLock(id, () -> {
            if (!"SAVE_FAILED".equals(requireOwned(userId, id).getStatus())) throw new BizException(409, "仅保存失败的任务可以放弃");
            aiGenerationMapper.finish(userId, id, "ABANDONED", "已放弃保存，预占额度已释放");
            return null;
        });
    }

    /** 分布式任务锁与数据库认领令牌共同保证任务只被执行一次，不在此持有用户上传锁。 */
    @Override
    public void runTask(long userId, String id) {
        withTaskLock(id, () -> {
            AiGeneration task = requireOwned(userId, id);
            String status = task.getStatus();
            if (!List.of("QUEUED", "SAVING").contains(status) || task.getWorkToken() != null) return null;
            String token = UUID.randomUUID().toString();
            boolean generate = status.equals("QUEUED");
            if (aiGenerationMapper.claim(userId, id, status, generate ? "GENERATING" : "SAVING",
                    token, generate ? timeoutSeconds + 60 : 120) != 1) return null;
            if (generate) {
                try {
                    byte[] bytes = aiImageClient.generate(task);
                    requireTaskLock(id);
                    aiGenerationMapper.storeResult(userId, id, token, bytes);
                } catch (Exception e) {
                    // 仅保存固定业务错误，不持久化供应商正文或异常堆栈中的Key。
                    String message = e instanceof BizException ? e.getMessage() : "生成未取得结果，额度已释放，请重新提交";
                    aiGenerationMapper.generationFailed(userId, id, token, message);
                }
            } else {
                saveResult(task, token);
            }
            return null;
        });
    }

    /** 云文件在短事务外上传；提交结果不明时先查数据库，避免误删已经成功入库的图片。 */
    private void saveResult(AiGeneration task, String token) {
        ImageFile stored = null;
        try {
            // 使用实体字段映射BLOB；Mapper直接返回byte[]会被MyBatis当作多行数组。
            AiGeneration result = aiGenerationMapper.resultData(task.getUserId(), task.getId());
            byte[] bytes = result == null ? null : result.getResultData();
            stored = imageFileService.storeGenerated(task.getUserId(), task.getId(), bytes);
            ImageFile image = stored;
            requireTaskLock(task.getId());
            uploadQuotaCache.completeGeneration(task.getUserId(), () -> {
                TransactionTemplate transaction = new TransactionTemplate(platformTransactionManagerProvider.getObject());
                return transaction.execute(status -> {
                    requireTaskLock(task.getId());
                    uploadQuotaCache.checkOwnership(task.getUserId());
                    if (aiGenerationMapper.succeed(task.getUserId(), task.getId(), token) != 1) {
                        throw new BizException(409, "任务已过期，不能保存结果");
                    }
                    imageMapper.insert(image);
                    return null;
                });
            });
        } catch (Exception e) {
            log.warn("AI save failed: taskId={}, errorType={}", task.getId(), e.getClass().getSimpleName());
            if (stored != null) {
                try {
                    if (imageMapper.findOwned(task.getUserId(), task.getId()) == null) imageFileService.discardUncommitted(stored);
                } catch (RuntimeException unknown) {
                    log.warn("AI save reconciliation required: taskId={}", task.getId());
                }
            }
            aiGenerationMapper.saveFailed(task.getUserId(), task.getId(), token);
        }
    }

    /** 仅扫描数据库认为过期的活动任务；恢复需先取得任务锁，不能干扰仍在执行的工作线程。 */
    @Override
    public void recoverTasks() {
        var tasks = page(new Page<AiGeneration>(1, 50, false), Wrappers.<AiGeneration>lambdaQuery()
                .isNotNull(AiGeneration::getActiveUserId)
                .and(q -> q.apply("work_deadline <= CURRENT_TIMESTAMP").or().apply("result_expires_at <= CURRENT_TIMESTAMP"))
                .orderByAsc(AiGeneration::getUpdateTime));
        for (AiGeneration candidate : tasks.getRecords()) {
            try {
                withTaskLock(candidate.getId(), () -> {
                    AiGeneration task = getOne(Wrappers.<AiGeneration>lambdaQuery().eq(AiGeneration::getId, candidate.getId())
                            .eq(AiGeneration::getUserId, candidate.getUserId()).isNotNull(AiGeneration::getActiveUserId)
                            .and(q -> q.apply("work_deadline <= CURRENT_TIMESTAMP").or().apply("result_expires_at <= CURRENT_TIMESTAMP")));
                    if (task == null) return null;
                    boolean expired = count(Wrappers.<AiGeneration>lambdaQuery().eq(AiGeneration::getId, task.getId())
                            .eq(AiGeneration::getUserId, task.getUserId()).apply("result_expires_at <= CURRENT_TIMESTAMP")) > 0;
                    if (expired) aiGenerationMapper.finish(task.getUserId(), task.getId(), "EXPIRED", "图片保存期限已过，额度已释放");
                    else if ("GENERATING".equals(task.getStatus())) {
                        aiGenerationMapper.finish(task.getUserId(), task.getId(), "FAILED", "生成超时或服务中断，未取得结果，额度已释放");
                    } else if ("SAVING".equals(task.getStatus())) {
                        aiGenerationMapper.saveFailed(task.getUserId(), task.getId(), task.getWorkToken());
                    }
                    return null;
                });
            } catch (Exception e) {
                // 下个周期继续；不记录模型配置、提示词或供应商异常内容。
                log.debug("AI recovery deferred: taskId={}", candidate.getId());
            }
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
        ImageFile image = "SUCCEEDED".equals(task.getStatus()) ? imageMapper.findOwned(task.getUserId(), task.getId()) : null;
        return GenerationVO.from(task, image == null ? null : ImageVO.from(image));
    }

    /** 使用任务独立锁；释放失败交给Redisson看门狗过期，不覆盖业务结果。 */
    private <T> T withTaskLock(String id, Supplier<T> operation) {
        var lock = redissonClient.getLock("image-hub:generation:" + id);
        if (!lock.tryLock()) throw new BizException(409, "任务正在处理，请稍后刷新");
        try {
            return operation.get();
        } finally {
            try { if (lock.isHeldByCurrentThread()) lock.unlock(); }
            catch (RuntimeException e) { log.warn("AI task lock release deferred: taskId={}", id); }
        }
    }

    private void requireTaskLock(String id) {
        if (!redissonClient.getLock("image-hub:generation:" + id).isHeldByCurrentThread()) {
            throw new BizException(503, "任务执行权已失效");
        }
    }
}
