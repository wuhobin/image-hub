package com.aurora.imagehub;

import com.aurora.imagehub.config.aigenerate.AiEndpointPolicy;
import com.aurora.imagehub.config.aigenerate.AiImageClient;
import com.aurora.imagehub.config.aigenerate.ModelKeyCipher;
import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.starter.webmvc.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 Spring AI 序列化和HTTP调用只访问本机桩，不连接任何付费服务。 */
class AiImageClientTest {

    @Test
    void sendsGptImageOptionsAndDoesNotRetryHttpFailures() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var json = new ObjectMapper();
        var requestBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var calls = new AtomicInteger();
        var status = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = (status.get() == 200
                    ? "{\"created\":1,\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\"}]}"
                    : "{\"error\":{\"message\":\"upstream-private-key\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        try {
            ModelKeyCipher modelKeyCipher = new ModelKeyCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            AiEndpointPolicy aiEndpointPolicy = mock(AiEndpointPolicy.class);
            when(aiEndpointPolicy.requirePublicHttps(anyString())).thenAnswer(call -> URI.create(call.getArgument(0)));
            AiImageClient aiImageClient = new AiImageClient(modelKeyCipher, aiEndpointPolicy, 5);
            AiGeneration task = new AiGeneration();
            task.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            task.setImagesPath("/v1/images/generations");
            task.setApiKeyCiphertext(modelKeyCipher.encrypt("test-only-key"));
            task.setModelCode("gpt-image-2");
            task.setPrompt("海边的书店");
            task.setQuality("high");
            var sizes = java.util.List.of("1024x1024", "2048x2048", "2880x2880", "1536x1024", "2160x1440", "3456x2304", "1024x1536", "1440x2160", "2304x3456", "1280x720", "2560x1440", "3840x2160", "720x1280", "1440x2560", "2160x3840", "1024x768", "2048x1536", "3200x2400", "768x1024", "1536x2048", "2400x3200", "1344x576", "2016x864", "3808x1632");
            for (String size : sizes) {
                task.setImageSize(size);
                assertThat(aiImageClient.generate(task)).isEqualTo(bytes.toByteArray());
                assertThat(json.readTree(requestBody.get()).path("size").asText()).isEqualTo(size);
            }
            assertThat(aiImageClient.generate(task)).isEqualTo(bytes.toByteArray());
            var body = json.readTree(requestBody.get());
            assertThat(body.path("model").asText()).isEqualTo("gpt-image-2");
            assertThat(body.path("n").asInt()).isEqualTo(1);
            assertThat(body.path("size").asText()).isEqualTo("3808x1632");
            assertThat(body.path("quality").asText()).isEqualTo("high");
            assertThat(body.path("prompt").asText()).isEqualTo("海边的书店");
            assertThat(body.hasNonNull("style")).isFalse();
            assertThat(body.hasNonNull("response_format")).isFalse();
            assertThat(authorization.get()).isEqualTo("Bearer test-only-key");
            status.set(429);
            assertThatThrownBy(() -> aiImageClient.generate(task)).isInstanceOf(BizException.class)
                    .hasMessageContaining("HTTP 429").hasMessageNotContaining("upstream-private-key");
            assertThat(calls.get()).isEqualTo(sizes.size() + 2);
        } finally { server.stop(0); }
    }

    @Test
    void encryptsKeysWithRandomNonceAndRejectsTamperingAndPrivateEndpoints() {
        ModelKeyCipher modelKeyCipher = new ModelKeyCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        String encrypted = modelKeyCipher.encrypt("test-key");
        assertThat(encrypted).doesNotContain("test-key").isNotEqualTo(modelKeyCipher.encrypt("test-key"));
        assertThat(modelKeyCipher.decrypt(encrypted)).isEqualTo("test-key");
        assertThatThrownBy(() -> new ModelKeyCipher("").encrypt("test-key")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> modelKeyCipher.decrypt(encrypted.substring(1))).isInstanceOf(BizException.class);
        AiEndpointPolicy aiEndpointPolicy = new AiEndpointPolicy();
        for (String value : new String[]{"http://example.com", "https://127.0.0.1", "https://169.254.169.254",
                "https://[::1]", "https://user:pass@example.com", "https://example.com:8443", "https://example.com/?api_key=secret"}) {
            assertThatThrownBy(() -> aiEndpointPolicy.requirePublicHttps(value)).isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> aiEndpointPolicy.validatePath("//evil.example/generate")).isInstanceOf(BizException.class);
    }
}
