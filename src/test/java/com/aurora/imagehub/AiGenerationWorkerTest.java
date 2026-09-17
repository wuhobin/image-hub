package com.aurora.imagehub;

import com.aurora.imagehub.config.aigenerate.AiGenerationWorker;
import com.aurora.imagehub.config.aigenerate.AiGenerationQueuedEvent;
import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.imagehub.service.AiGenerationService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证真实虚拟线程执行、并发许可回收和停机中断，不调用模型或云存储。 */
class AiGenerationWorkerTest {

    /** 网络等待期间不突破并发上限，异常释放许可，新任务独立建线程，停机可中断等待。 */
    @Test
    @SuppressWarnings("unchecked")
    void virtualTasksRespectConcurrencyReleaseOnFailureAndStopOnShutdown() throws Exception {
        AiGenerationService aiGenerationService = mock(AiGenerationService.class);
        var batches = new ConcurrentLinkedQueue<List<AiGeneration>>();
        batches.add(List.of(task("first"), task("second")));
        batches.add(List.of(task("third")));
        when(aiGenerationService.page(any(Page.class), any())).thenAnswer(call -> {
            Page<AiGeneration> page = call.getArgument(0);
            var batch = batches.poll();
            return page.setRecords(batch == null ? List.of() : batch);
        });
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var thirdStarted = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var threads = ConcurrentHashMap.<Thread>newKeySet();
        doAnswer(call -> {
            threads.add(Thread.currentThread());
            if ("third".equals(call.getArgument(1))) {
                thirdStarted.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return null;
            }
            started.countDown();
            release.await();
            if ("first".equals(call.getArgument(1))) throw new IllegalStateException("simulated task failure");
            return null;
        }).when(aiGenerationService).runTask(anyLong(), anyString());

        var worker = new AiGenerationWorker(aiGenerationService, 2);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AiGenerationWorker.class, () -> worker);
            context.refresh();
            context.publishEvent(new AiGenerationQueuedEvent());
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll();
            verify(aiGenerationService, times(1)).page(any(Page.class), any());
            assertThat(thirdStarted.getCount()).isEqualTo(1);
            release.countDown();
            // 无定时调度器、无手动 poll：结束回调必须自动补位。
            assertThat(thirdStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threads).hasSize(3).allSatisfy(thread -> {
                assertThat(thread.isVirtual()).isTrue();
                assertThat(thread.getName()).startsWith("ai-generation-");
            });
            worker.stop();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            verify(aiGenerationService).runTask(1L, "third");
        } finally {
            release.countDown();
            worker.stop();
        }
    }

    /** 多入口同时派发同一条尚未认领记录时，本实例只能执行一次。 */
    @Test
    @SuppressWarnings("unchecked")
    void repeatedNotificationsDoNotDispatchTheSameTaskTwice() throws Exception {
        AiGenerationService aiGenerationService = mock(AiGenerationService.class);
        when(aiGenerationService.page(any(Page.class), any())).thenAnswer(call -> {
            Page<AiGeneration> page = call.getArgument(0);
            return page.setRecords(List.of(task("same")));
        });
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            release.await();
            // 模拟认领前数据库故障：不能立即无限重试同一排队任务。
            throw new IllegalStateException("claim unavailable");
        }).when(aiGenerationService).runTask(1L, "same");
        var worker = new AiGenerationWorker(aiGenerationService, 2);
        try {
            worker.poll();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll();
            worker.poll();
            verify(aiGenerationService, times(1)).runTask(1L, "same");
            release.countDown();
            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    verify(aiGenerationService, times(1)).runTask(1L, "same"));
            worker.stop();
            clearInvocations(aiGenerationService);
            worker.poll();
            verifyNoInteractions(aiGenerationService);
        } finally {
            release.countDown();
            worker.stop();
        }
    }

    /** 构造领取结果，仅填执行器实际需要的任务归属。 */
    private AiGeneration task(String id) {
        var task = new AiGeneration();
        task.setId(id);
        task.setUserId(1L);
        return task;
    }
}
