package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/** 持久化生图任务；活动用户唯一约束保证每人最多一个未完成任务。 */
@Getter
@Setter
@TableName("hub_ai_generation")
public class AiGeneration {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;

    private String requestId;

    /** 终态清空；唯一索引将并发约束落实到数据库。 */
    private Long activeUserId;

    private Long modelId;

    private String modelName;

    private String modelCode;

    /** 提交时快照，停用或修改模型不会改变已接收任务。 */
    private String baseUrl;

    private String imagesPath;

    private String apiKeyCiphertext;

    private String prompt;

    private String imageSize;

    private String quality;

    private String status;

    private String errorMessage;

    /** 每次认领使用不同标记，阻止超时工作线程提交过期结果。 */
    private String workToken;

    private Date workDeadline;

    private Date resultExpiresAt;

    /** 最多 10 MB，列表不加载；入库成功、放弃或过期后清空。 */
    @TableField(select = false)
    private byte[] resultData;

    /** 审计时间完全由数据库生成和维护。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    @TableLogic
    private Integer deleted = 0;
}
