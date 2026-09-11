package com.aurora.imagehub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=dev")
class ImageHubApplicationTest extends InfrastructureTestSupport {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Test
    void shouldServeHealthWithPlatformResponseAndTraceId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "image-hub-smoke-test");

        ResponseEntity<JsonNode> response = testRestTemplate.exchange(
                "/api/health", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asInt()).isEqualTo(200);
        assertThat(response.getBody().path("data").path("application").asText()).isEqualTo("image-hub");
        assertThat(response.getBody().path("data").path("status").asText()).isEqualTo("UP");
        assertThat(response.getBody().path("traceId").asText()).isEqualTo("image-hub-smoke-test");
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("image-hub-smoke-test");
    }

    @Test
    void shouldExposeApiDocumentationInDevProfile() {
        ResponseEntity<JsonNode> response = testRestTemplate.getForEntity(
                "/v3/api-docs/image-hub", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("info").path("title").asText()).isEqualTo("Image Hub API");
        assertThat(response.getBody().path("paths").has("/api/health")).isTrue();
    }
}
