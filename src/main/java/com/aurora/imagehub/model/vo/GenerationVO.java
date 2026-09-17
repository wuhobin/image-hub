package com.aurora.imagehub.model.vo;

import com.aurora.imagehub.model.entity.AiGeneration;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 用户自己的任务进度；不暴露密钥、供应商地址、临时数据及工作令牌。 */
@Getter
@Setter
@NoArgsConstructor
public class GenerationVO {

    private String id;

    private String requestId;

    private String modelName;

    private String prompt;

    private String size;

    private String quality;

    private String status;

    private String errorMessage;

    private Date createTime;

    /** 实测生成及保存耗时，单位秒、精确到毫秒；不含排队，旧记录或中断未实测时为空。 */
    private BigDecimal durationSeconds;

    /** 兼容旧响应字段；结果不再暂存，固定为空。 */
    private Date resultExpiresAt;

    private ImageVO image;

    /** 结果图片仅由调用方按当前用户归属查询后填入。 */
    public static GenerationVO from(AiGeneration task, ImageVO image) {
        GenerationVO result = new GenerationVO();
        result.setId(task.getId());
        result.setRequestId(task.getRequestId());
        result.setModelName(task.getModelName());
        result.setPrompt(task.getPrompt());
        result.setSize(task.getImageSize());
        result.setQuality(task.getQuality());
        result.setStatus(task.getStatus());
        result.setErrorMessage(task.getErrorMessage());
        result.setCreateTime(task.getCreateTime());
        if (task.getDurationMillis() != null) {
            result.setDurationSeconds(BigDecimal.valueOf(task.getDurationMillis(), 3));
        }
        result.setImage(image);
        return result;
    }
}
