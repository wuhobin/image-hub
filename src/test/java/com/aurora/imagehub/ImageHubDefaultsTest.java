package com.aurora.imagehub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("default")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImageHubDefaultsTest extends InfrastructureTestSupport {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/doc.html", "/swagger-ui/index.html"})
    void shouldNotExposeDocumentationByDefault(String path) {
        ResponseEntity<JsonNode> response = testRestTemplate.getForEntity(path, JsonNode.class);

        // 平台异常处理约定 HTTP 200 + 业务码 404，不能仅判断 HTTP 状态。
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asInt()).isEqualTo(404);
    }
}
