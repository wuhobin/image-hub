package com.aurora.imagehub.model.vo;

import com.aurora.imagehub.model.entity.AiModelConfig;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 用户可见模型选项，不含调用地址、密钥和管理信息。 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelVO {

    private Long id;

    private String name;

    private List<String> sizes;

    private String defaultSize;

    private List<String> qualities;

    private String defaultQuality;

    private Integer pointsCost;

    /** 仅映射允许公开的选择项。 */
    public static AiModelVO from(AiModelConfig model) {
        AiModelVO result = new AiModelVO();
        result.setId(model.getId());
        result.setName(model.getName());
        result.setSizes(List.of(model.getSizes().split(",")));
        result.setDefaultSize(model.getDefaultSize());
        result.setQualities(List.of(model.getQualities().split(",")));
        result.setDefaultQuality(model.getDefaultQuality());
        result.setPointsCost(model.getPointsCost());
        return result;
    }
}
