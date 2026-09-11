package com.aurora.imagehub.model.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

/** HTTP 存活状态，不代表数据库、Redis 等依赖的健康状态。 */
@Schema(description = "应用运行状态")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HealthVO {
    @Schema(description = "应用名称", example = "image-hub")
    private String application;

    @Schema(description = "运行状态", example = "UP")
    private String status;
}
