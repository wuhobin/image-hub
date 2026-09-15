package com.aurora.imagehub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证后端不再托管前端，业务接口仍鉴权，生产文档保持关闭。 */
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendRoutingTest extends InfrastructureTestSupport {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Test
    void protectsApiWithoutServingFrontendOrDocumentation() {
        for (String path : new String[]{"/api/app/images", "/api/app/auth/me", "/api/admin/users"}) {
            assertThat(testRestTemplate.getForObject(path, JsonNode.class).path("code").asInt()).isEqualTo(401);
        }
        for (String path : new String[]{"/", "/index.html", "/login", "/register", "/history", "/profile",
                "/favicon.svg", "/assets/missing.js", "/doc.html", "/v3/api-docs",
                "/swagger-ui/index.html", "/api/nonexistent", "/api/auth/me", "/api/images"}) {
            var response = testRestTemplate.getForEntity(path, JsonNode.class);
            assertThat(response.getHeaders().getContentType()).isNotNull();
            assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
            assertThat(response.getBody().path("code").asInt()).isEqualTo(404);
        }
        assertThat(testRestTemplate.postForObject("/api/app/auth/login",
                java.util.Map.of("username", "", "password", ""), JsonNode.class).path("code").asInt()).isEqualTo(400);
        assertThat(testRestTemplate.postForObject("/api/auth/login",
                java.util.Map.of("username", "", "password", ""), JsonNode.class).path("code").asInt()).isEqualTo(404);
    }
}
