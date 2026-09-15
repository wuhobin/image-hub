package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/** 管理员维护的生图模型配置；密文仅供服务端解密调用，不作为接口响应。 */
@Getter
@Setter
@TableName("hub_ai_model")
public class AiModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String modelCode;

    private String baseUrl;

    private String imagesPath;

    private String apiKeyCiphertext;

    /** 逗号分隔的受控选项，不允许客户端传入任意模型参数。 */
    private String sizes;

    private String defaultSize;

    private String qualities;

    private String defaultQuality;

    private Boolean enabled = false;

    private Integer sortOrder = 0;

    /** 审计时间完全由数据库生成和维护。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    @TableLogic
    private Integer deleted = 0;
}
