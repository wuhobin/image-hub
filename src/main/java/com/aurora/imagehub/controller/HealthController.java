package com.aurora.imagehub.controller;

import com.aurora.imagehub.model.response.HealthResponse;
import com.aurora.starter.webmvc.domain.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "应用状态")
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String applicationName;

    public HealthController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    @Operation(summary = "检查应用是否可访问")
    @GetMapping
    public Result<HealthResponse> health() {
        return Result.data(new HealthResponse(applicationName, "UP"));
    }
}
