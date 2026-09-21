package com.aurora.imagehub.model.vo.admin;

import com.aurora.imagehub.model.entity.AiModelConfig;
import java.util.Date;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 模型管理响应仅报告密钥是否已配置，任何时候都不回显完整 Key 或密文。 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelConfigVO {

    private Long id;

    private String name;

    private String modelCode;

    private String baseUrl;

    private String imagesPath;

    private boolean keyConfigured;

    private List<String> sizes;

    private String defaultSize;

    private List<String> qualities;

    private String defaultQuality;

    private Integer pointsCost;

    private Boolean enabled;

    private Integer sortOrder;

    private Date updateTime;

    /** 明确列举管理字段，避免敏感配置随实体序列化泄露。 */
    public static AiModelConfigVO from(AiModelConfig model) {
        AiModelConfigVO result = new AiModelConfigVO();
        result.setId(model.getId());
        result.setName(model.getName());
        result.setModelCode(model.getModelCode());
        result.setBaseUrl(model.getBaseUrl());
        result.setImagesPath(model.getImagesPath());
        result.setKeyConfigured(model.getApiKeyCiphertext() != null && !model.getApiKeyCiphertext().isBlank());
        result.setSizes(List.of(model.getSizes().split(",")));
        result.setDefaultSize(model.getDefaultSize());
        result.setQualities(List.of(model.getQualities().split(",")));
        result.setDefaultQuality(model.getDefaultQuality());
        result.setPointsCost(model.getPointsCost());
        result.setEnabled(model.getEnabled());
        result.setSortOrder(model.getSortOrder());
        result.setUpdateTime(model.getUpdateTime());
        return result;
    }
}
