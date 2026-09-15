package com.aurora.imagehub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用轻量任务轮询；持久化队列与业务状态由AI任务服务维护。 */
@Configuration
@EnableScheduling
public class AiSchedulingConfig {
}
