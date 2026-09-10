package com.aurora.imagehub.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "应用运行状态")
public record HealthResponse(
        @Schema(description = "应用名称", example = "image-hub") String application,
        @Schema(description = "运行状态", example = "UP") String status) {
}
