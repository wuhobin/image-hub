package com.aurora.imagehub.config.aigenerate;

import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.imagehub.service.AiGenerationService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 小规模任务执行器；队列由数据库承载，进程内只保留有界并发，不丢弃重启前的排队任务。 */
@Component
@ConditionalOnProperty(name = "image-hub.ai.worker-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class AiGenerationWorker {

    private final AiGenerationService aiGenerationService;

    private final ExecutorService executorService;

    private final Semaphore semaphore;

    public AiGenerationWorker(AiGenerationService aiGenerationService,
                              @Value("${image-hub.ai.concurrency:2}") int concurrency) {
        if (concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("AI concurrency must be between 1 and 8");
        this.aiGenerationService = aiGenerationService;
        this.executorService = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("ai-generation-", 0).factory());
        this.semaphore = new Semaphore(concurrency);
    }

    /** 短轮询只派发可用容量；多实例由数据库认领和任务锁排除重复执行。 */
    @Scheduled(fixedDelayString = "${image-hub.ai.poll-ms:2000}", initialDelay = 5000)
    public void poll() {
        try {
            aiGenerationService.recoverTasks();
            int capacity = semaphore.availablePermits();
            if (capacity == 0) return;
            var tasks = aiGenerationService.page(new Page<AiGeneration>(1, capacity, false),
                    Wrappers.<AiGeneration>lambdaQuery().in(AiGeneration::getStatus, "QUEUED", "SAVING")
                            .isNull(AiGeneration::getWorkToken)
                            .and(q -> q.isNull(AiGeneration::getResultExpiresAt).or().apply("result_expires_at > CURRENT_TIMESTAMP"))
                            .orderByAsc(AiGeneration::getCreateTime, AiGeneration::getId));
            for (AiGeneration task : tasks.getRecords()) {
                if (!semaphore.tryAcquire()) break;
                try {
                    executorService.execute(() -> {
                        try { aiGenerationService.runTask(task.getUserId(), task.getId()); }
                        catch (Exception e) { log.debug("AI task processing deferred: taskId={}", task.getId()); }
                        finally { semaphore.release(); }
                    });
                } catch (RejectedExecutionException e) {
                    semaphore.release();
                }
            }
        } catch (Exception e) {
            log.warn("AI worker temporarily unavailable; tasks remain in database");
        }
    }

    /** 停机中断不会重新发起生成；遗留执行状态由数据库截止时间触发恢复。 */
    @PreDestroy
    public void stop() {
        executorService.shutdownNow();
    }
}
