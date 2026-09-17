package com.aurora.imagehub.config.aigenerate;

import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.imagehub.constants.AiGenerationConstants.TaskStatus;
import com.aurora.imagehub.service.AiGenerationService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PreDestroy;
import java.util.HashSet;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.event.EventListener;
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

    private final ReentrantLock dispatchLock = new ReentrantLock();

    private final HashSet<String> dispatched = new HashSet<>();

    public AiGenerationWorker(AiGenerationService aiGenerationService,
                              @Value("${image-hub.ai.concurrency:2}") int concurrency) {
        if (concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("AI concurrency must be between 1 and 8");
        this.aiGenerationService = aiGenerationService;
        // 每个任务独占一个虚拟线程；并发由信号量控制，避免同时生成过多大图片。
        this.executorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ai-generation-", 0).factory());
        this.semaphore = new Semaphore(concurrency);
    }

    /** 提交通知与定时兜底共用派发入口；仅串行短查询，模型请求在独立虚拟线程执行。 */
    @EventListener(AiGenerationQueuedEvent.class)
    @Scheduled(fixedDelayString = "${image-hub.ai.poll-ms:2000}", initialDelay = 5000)
    public void poll() {
        dispatchLock.lock();
        try {
            if (executorService.isShutdown()) return;
            int capacity = semaphore.availablePermits();
            if (capacity == 0) return;
            var tasks = aiGenerationService.page(new Page<AiGeneration>(1, capacity, false),
                    Wrappers.<AiGeneration>lambdaQuery().eq(AiGeneration::getStatus, TaskStatus.QUEUED.name())
                            .isNull(AiGeneration::getWorkToken)
                            .notIn(!dispatched.isEmpty(), AiGeneration::getId, dispatched)
                            .orderByAsc(AiGeneration::getCreateTime, AiGeneration::getId));
            for (AiGeneration task : tasks.getRecords()) {
                if (dispatched.contains(task.getId())) continue;
                if (!semaphore.tryAcquire()) break;
                dispatched.add(task.getId());
                try {
                    executorService.execute(() -> {
                        boolean completed = false;
                        try {
                            aiGenerationService.runTask(task.getUserId(), task.getId());
                            completed = true;
                        }
                        catch (Exception e) {
                            log.info("AI 创作任务处理异常，等待后续轮询：taskId={}", task.getId(), e);
                        }
                        finally {
                            dispatchLock.lock();
                            try {
                                dispatched.remove(task.getId());
                                semaphore.release();
                            } finally {
                                dispatchLock.unlock();
                            }
                        }
                        // 未处理异常可能使任务仍在排队；留待定时兜底，避免立即重复领取形成忙循环。
                        if (completed) poll();
                    });
                } catch (RejectedExecutionException e) {
                    dispatched.remove(task.getId());
                    semaphore.release();
                }
            }
        } catch (Exception e) {
            log.info("AI 创作调度暂不可用，排队任务仍保留在数据库", e);
        } finally {
            dispatchLock.unlock();
        }
    }

    /** 停机中断不会重新发起生成；遗留执行状态由后续用户访问按截止时间释放。 */
    @PreDestroy
    public void stop() {
        executorService.shutdownNow();
    }
}
