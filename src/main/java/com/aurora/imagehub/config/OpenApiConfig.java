package com.aurora.imagehub.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "springdoc.api-docs", name = "enabled", havingValue = "true")
public class OpenApiConfig {

    @Bean
    public OpenAPI imageHubOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Image Hub API")
                .description("Image Hub 单体应用接口文档")
                .version("0.0.1"));
    }
}
