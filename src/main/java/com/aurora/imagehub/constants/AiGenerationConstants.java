package com.aurora.imagehub.constants;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 图片生成任务的状态与执行阶段；状态名对应数据库及接口值，阶段说明用于中文日志。 */
public final class AiGenerationConstants {

    private AiGenerationConstants() {
    }

    /** 持久化任务状态；name() 为现有状态码，修改名称须同步数据库及接口协议。 */
    @Getter
    @RequiredArgsConstructor
    public enum TaskStatus {
        QUEUED("排队中"),

        GENERATING("生成中"),

        SAVING("保存中"),

        SUCCEEDED("已完成"),

        FAILED("失败");

        private final String description;
    }

    /** 执行过程中的诊断阶段；仅用于流程判断和日志，不作为持久化任务状态。 */
    @Getter
    @RequiredArgsConstructor
    public enum TaskStage {
        QUEUE_WAIT("排队等待"),

        PREPARE_REQUEST("准备请求"),

        MODEL_REQUEST("模型请求"),

        VALIDATE_RESPONSE("响应校验"),

        DECODE_BASE64("Base64 解码"),

        DOWNLOAD_IMAGE("下载图片"),

        SAVE_IMAGE("保存图片"),

        VALIDATE_IMAGE("图片校验"),

        UPLOAD_IMAGE("上传七牛"),

        PERSIST_RESULT("入库与额度结算"),

        TOTAL("总处理");

        private final String description;
    }
}
