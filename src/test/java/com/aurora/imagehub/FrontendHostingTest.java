package com.aurora.imagehub;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证生产环境真实前端产物、页面刷新，以及静态页面和业务接口之间的鉴权边界。 */
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrontendHostingTest extends InfrastructureTestSupport {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Test
    void servesBuiltFrontendWhileProtectingApiAndHidingDocumentation() {
        String index = testRestTemplate.getForObject("/index.html", String.class);
        assertThat(index).contains("<div id=\"root\"></div>");
        for (String path : new String[]{"/", "/login", "/register", "/history"}) {
            var page = testRestTemplate.getForEntity(path, String.class);
            assertThat(page.getStatusCode().value()).isEqualTo(200);
            assertThat(page.getHeaders().getContentType()).isNotNull();
            assertThat(page.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
            assertThat(page.getBody()).isEqualTo(index);
        }

        var assets = Pattern.compile("(?:src|href)=\"(/assets/[^\"]+)\"").matcher(index);
        int assetCount = 0;
        while (assets.find()) {
            var asset = testRestTemplate.getForEntity(assets.group(1), String.class);
            assertThat(asset.getStatusCode().value()).isEqualTo(200);
            assertThat(asset.getBody()).isNotBlank().doesNotContain("<div id=\"root\"></div>");
            assetCount++;
        }
        assertThat(assetCount).isGreaterThanOrEqualTo(2);
        assertThat(testRestTemplate.getForObject("/favicon.svg", String.class)).contains("<svg");

        for (String path : new String[]{"/api/images", "/api/auth/me"}) {
            assertThat(testRestTemplate.getForObject(path, JsonNode.class).path("code").asInt()).isEqualTo(401);
        }
        for (String path : new String[]{"/doc.html", "/v3/api-docs", "/swagger-ui/index.html", "/assets/missing.js", "/api/nonexistent"}) {
            var response = testRestTemplate.getForEntity(path, JsonNode.class);
            assertThat(response.getHeaders().getContentType()).isNotNull();
            assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
            assertThat(response.getBody().path("code").asInt()).isEqualTo(404);
        }
        assertThat(testRestTemplate.postForObject("/api/auth/login",
                java.util.Map.of("username", "", "password", ""), JsonNode.class).path("code").asInt()).isEqualTo(400);
    }
}
