package com.aurora.imagehub;

import com.aurora.imagehub.config.aigenerate.AiEndpointPolicy;
import com.aurora.imagehub.config.aigenerate.AiImageClient;
import com.aurora.imagehub.config.aigenerate.AiImageLimits;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 Spring AI 序列化和HTTP调用只访问本机桩，不连接任何付费服务。 */
class AiImageClientTest {

    private final org.apache.logging.log4j.core.Logger aiImageClientLogger =
            (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(AiImageClient.class);

    private final ByteArrayOutputStream logOutput = new ByteArrayOutputStream();

    private org.apache.logging.log4j.core.appender.OutputStreamAppender outputStreamAppender;

    private org.apache.logging.log4j.Level previousLevel;

    @BeforeEach
    void captureDiagnostics() {
        previousLevel = aiImageClientLogger.getLevel();
        aiImageClientLogger.setLevel(org.apache.logging.log4j.Level.INFO);
        outputStreamAppender = org.apache.logging.log4j.core.appender.OutputStreamAppender.newBuilder()
                .setName("AI-diagnostics-test").setTarget(logOutput)
                .setLayout(org.apache.logging.log4j.core.layout.PatternLayout.newBuilder().withPattern("%level %message%n%throwable").build())
                .build();
        outputStreamAppender.start();
        aiImageClientLogger.addAppender(outputStreamAppender);
    }

    @AfterEach
    void restoreLogging() {
        aiImageClientLogger.removeAppender(outputStreamAppender);
        aiImageClientLogger.setLevel(previousLevel);
        outputStreamAppender.stop();
    }

    private String diagnosticLogs() {
        return logOutput.toString(StandardCharsets.UTF_8);
    }

    /** 验证 Spring 默认值、自定义连接时间实际传入 HTTP 客户端，以及非法配置阻止启动。 */
    @Test
    void bindsAndValidatesConnectionTimeoutSeparatelyFromResponseTimeout() {
        var contextRunner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(ModelKeyCipher.class, () -> mock(ModelKeyCipher.class))
                .withBean(AiEndpointPolicy.class, () -> mock(AiEndpointPolicy.class))
                .withBean(AiImageLimits.class, () -> new AiImageLimits("10MB"))
                .withUserConfiguration(AiImageClient.class);
        for (int seconds : new int[]{30, 17}) {
            var configured = seconds == 30 ? contextRunner
                    : contextRunner.withPropertyValues("image-hub.ai.connect-timeout-seconds=" + seconds);
            configured.run(context -> {
                assertThat(context).hasNotFailed();
                var client = context.getBean(AiImageClient.class);
                var httpClient = (java.net.http.HttpClient) org.springframework.test.util.ReflectionTestUtils.getField(client, "httpClient");
                assertThat(httpClient.connectTimeout()).contains(java.time.Duration.ofSeconds(seconds));
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(client, "timeoutSeconds")).isEqualTo(300);
            });
        }
        for (int invalid : new int[]{0, 61}) {
            contextRunner.withPropertyValues("image-hub.ai.connect-timeout-seconds=" + invalid).run(context ->
                    assertThat(context).hasFailed().getFailure().hasRootCauseInstanceOf(IllegalArgumentException.class));
        }
    }

    @Test
    void sendsGptImageOptionsAndDoesNotRetryHttpFailures() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var json = new ObjectMapper();
        var requestBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var calls = new AtomicInteger();
        var status = new AtomicInteger(200);
        var errorBody = new AtomicReference<>("{\"error\":{\"message\":\"Too many requests\"},\"request_id\":\"upstream-request-id\"}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = (status.get() == 200
                    ? "{\"created\":1,\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\"}]}"
                    : errorBody.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        try {
            ModelKeyCipher modelKeyCipher = new ModelKeyCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            AiEndpointPolicy aiEndpointPolicy = mock(AiEndpointPolicy.class);
            when(aiEndpointPolicy.requirePublicHttps(anyString())).thenAnswer(call -> URI.create(call.getArgument(0)));
            AiImageClient aiImageClient = new AiImageClient(modelKeyCipher, aiEndpointPolicy, new AiImageLimits("10MB"), 5, 30);
            AiGeneration task = new AiGeneration();
            task.setId("request-response-test-task");
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
                assertThat(json.readTree(requestBody.get()).path("moderation").asText()).isEqualTo("low");
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
                    .hasMessage("Too many requests");
            assertThat(calls.get()).isEqualTo(sizes.size() + 2);
            assertThat(diagnosticLogs()).contains("AI 创作失败：", "httpStatus=429", "AI 创作耗时：", "阶段=Base64 解码", " 秒",
                            "BizException: Too many requests",
                            "AiImageClient$1.handleError")
                    .doesNotContain("elapsedMs=", "AI generation timing")
                    .contains("OpenAI 请求：taskId=request-response-test-task, 请求参数=" + requestBody.get(),
                            "OpenAI 响应：taskId=request-response-test-task, httpStatus=200, 响应正文={\"created\":1,\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\"}]}",
                            "OpenAI 响应：taskId=request-response-test-task, httpStatus=429, 正文截断=false, 响应正文=" + errorBody.get())
                    .doesNotContain("Authorization", "test-only-key", task.getApiKeyCiphertext());

            String unsafeMessage = "The generated images appear to be unsafe. Try modifying the prompts or the seeds.";
            status.set(451);
            errorBody.set(json.writeValueAsString(java.util.Map.of("error_code", "image_unsafe", "message", unsafeMessage)));
            String expected = unsafeMessage;
            assertThatThrownBy(() -> aiImageClient.generate(task)).isInstanceOf(BizException.class).hasMessage(expected);
            assertThat(diagnosticLogs()).contains(expected);

            status.set(429);
            errorBody.set("{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"Too many requests\"}}");
            assertThatThrownBy(() -> aiImageClient.generate(task)).isInstanceOf(BizException.class)
                    .hasMessage("Too many requests");

            status.set(502);
            for (String invalid : new String[]{"<html>Bad gateway</html>", "{broken", "{}", "", "x".repeat(8193)}) {
                errorBody.set(invalid);
                assertThatThrownBy(() -> aiImageClient.generate(task)).isInstanceOf(BizException.class)
                        .hasMessage("模型服务拒绝请求（HTTP 502），请检查配置或稍后重试");
            }
            assertThat(diagnosticLogs()).contains("httpStatus=502, 正文截断=true, 响应正文=" + "x".repeat(8192))
                    .doesNotContain("x".repeat(8193));
            errorBody.set(json.writeValueAsString(java.util.Map.of("message", "😀".repeat(300))));
            var longError = catchThrowableOfType(BizException.class, () -> aiImageClient.generate(task));
            assertThat(longError.getMessage()).isEqualTo("😀".repeat(300));
            String rawMessage = "  poll failed: 451 " + json.writeValueAsString(java.util.Map.of("error_code", "image_unsafe", "message", unsafeMessage)) + "\n";
            errorBody.set(json.writeValueAsString(java.util.Map.of("error", java.util.Map.of("message", rawMessage))));
            assertThatThrownBy(() -> aiImageClient.generate(task)).isInstanceOf(BizException.class).hasMessage(rawMessage);
            assertThat(calls.get()).isEqualTo(sizes.size() + 11);

        } finally { server.stop(0); }
    }

    @Test
    void logsTimeoutAndMalformedResponseWithOriginalMessagesAndCauseStack() throws Exception {
        var calls = new AtomicInteger();
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            int attempt = calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                if (attempt == 2) release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                byte[] body = "{\"data\":\"invalid-response-value\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // 超时后客户端已断开。
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var modelKeyCipher = new ModelKeyCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            var aiEndpointPolicy = mock(AiEndpointPolicy.class);
            when(aiEndpointPolicy.requirePublicHttps(anyString())).thenAnswer(call -> URI.create(call.getArgument(0)));
            var aiImageClient = new AiImageClient(modelKeyCipher, aiEndpointPolicy, new AiImageLimits("10MB"), 1, 30);
            var task = new AiGeneration();
            task.setId("diagnostic-test-task");
            task.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            task.setImagesPath("/v1/images/generations");
            task.setApiKeyCiphertext(modelKeyCipher.encrypt("private-test-key"));
            task.setModelCode("gpt-image-2");
            task.setPrompt("private-test-prompt");
            task.setImageSize("1024x1024");
            task.setQuality("medium");
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> aiImageClient.generate(task)).isInstanceOf(BizException.class)
                        .hasMessageContaining("生成请求超时或未取得有效结果");
            }
            assertThat(calls.get()).isEqualTo(2);
            assertThat(diagnosticLogs()).contains("INFO", "taskId=diagnostic-test-task", "阶段=模型请求",
                            "httpStatus=200", "httpStatus=-1", "耗时=", "模型连接超时=30 秒", "模型响应超时=1 秒", "TimeoutException", "Caused by:")
                    .contains("JSON parse error: Cannot deserialize value", "HttpTimeoutException: Request timed out",
                            "AiImageClient.generate")
                    .contains(task.getPrompt(), "响应正文={\"data\":\"invalid-response-value\"}")
                    .doesNotContain("Authorization", "private-test-key", task.getApiKeyCiphertext());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedJsonBeforeParsingIncludingChunkedResponsesWithoutRetry() throws Exception {
        var calls = new AtomicInteger();
        var chunked = new java.util.concurrent.atomic.AtomicBoolean();
        int limit = new AiImageLimits("1MB").getMaxResponseBytes();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, chunked.get() ? 0 : limit + 1L);
            try (var body = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                java.util.Arrays.fill(buffer, (byte) ' ');
                for (int sent = 0; sent <= limit; sent += buffer.length) body.write(buffer);
            } catch (java.io.IOException ignored) {
                // 客户端发现超限会主动关闭流。
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var modelKeyCipher = new ModelKeyCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            var aiEndpointPolicy = mock(AiEndpointPolicy.class);
            when(aiEndpointPolicy.requirePublicHttps(anyString())).thenAnswer(call -> URI.create(call.getArgument(0)));
            var aiImageClient = new AiImageClient(modelKeyCipher, aiEndpointPolicy, new AiImageLimits("1MB"), 5, 30);
            var task = new AiGeneration();
            task.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            task.setImagesPath("/v1/images/generations");
            task.setApiKeyCiphertext(modelKeyCipher.encrypt("test-only-key"));
            task.setModelCode("gpt-image-2");
            task.setPrompt("test");
            task.setQuality("medium");
            task.setImageSize("1024x1024");
            for (boolean transferChunked : new boolean[]{false, true}) {
                chunked.set(transferChunked);
                assertThatThrownBy(() -> aiImageClient.generate(task)).isInstanceOf(BizException.class)
                        .hasMessageContaining("模型响应超过大小限制");
            }
            assertThat(calls.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @Test
    void validatesGeneratedImagePixelsAndRejectsTruncatedContent() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        assertThat(new com.aurora.imagehub.config.aigenerate.GeneratedImageFile(bytes.toByteArray(), new AiImageLimits("10MB")).getSize()).isEqualTo(bytes.size());
        for (int length : new int[]{8, 33, bytes.size() / 2}) {
            assertThatThrownBy(() -> new com.aurora.imagehub.config.aigenerate.GeneratedImageFile(java.util.Arrays.copyOf(bytes.toByteArray(), length), new AiImageLimits("10MB")))
                    .isInstanceOf(BizException.class);
        }
        byte[] oversized = bytes.toByteArray();
        // PNG IHDR中的宽高位于16、20字节；尺寸检查应发生在像素分配之前。
        java.nio.ByteBuffer.wrap(oversized).putInt(16, 100_000).putInt(20, 100_000);
        assertThatThrownBy(() -> new com.aurora.imagehub.config.aigenerate.GeneratedImageFile(oversized, new AiImageLimits("10MB"))).isInstanceOf(BizException.class);
    }

    /** 覆盖配置增大、Base64 填充边界，以及有长度和分块 URL 下载。 */
    @Test
    void configuredSizeAppliesToDecodedBase64AndUrlDownloads() throws Exception {
        var image = new AtomicReference<>(new byte[4]);
        var urlResponse = new java.util.concurrent.atomic.AtomicBoolean();
        var chunked = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            byte[] bytes = image.get();
            exchange.sendResponseHeaders(200, chunked.get() ? 0 : bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
            catch (java.io.IOException ignored) { /* 超限客户端关闭连接。 */ }
            finally { exchange.close(); }
        });
        server.createContext("/v1/images/generations", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String output = urlResponse.get()
                    ? "\"url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/image\""
                    : "\"b64_json\":\"" + Base64.getEncoder().encodeToString(image.get()) + "\"";
            byte[] body = ("{\"data\":[{" + output + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) { response.write(body); }
            catch (java.io.IOException ignored) { /* 超限客户端关闭连接。 */ }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var modelKeyCipher = new ModelKeyCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            var aiEndpointPolicy = mock(AiEndpointPolicy.class);
            when(aiEndpointPolicy.requirePublicHttps(anyString())).thenAnswer(call -> URI.create(call.getArgument(0)));
            var task = new AiGeneration();
            task.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            task.setImagesPath("/v1/images/generations");
            task.setApiKeyCiphertext(modelKeyCipher.encrypt("test-only-key"));
            task.setModelCode("gpt-image-2");
            task.setPrompt("test");
            task.setQuality("medium");
            task.setImageSize("1024x1024");
            var small = new AiImageClient(modelKeyCipher, aiEndpointPolicy, new AiImageLimits("4B"), 5, 30);
            for (boolean useUrl : new boolean[]{false, true}) {
                urlResponse.set(useUrl);
                for (boolean useChunks : new boolean[]{false, true}) {
                    chunked.set(useChunks);
                    image.set(new byte[4]);
                    assertThat(small.generate(task)).hasSize(4);
                    // 4、5、6 字节的 Base64 都为 8 字符，必须校验解码后的实际长度。
                    image.set(new byte[5]);
                    assertThatThrownBy(() -> small.generate(task)).isInstanceOf(BizException.class).hasMessageContaining("4B");
                }
            }
            urlResponse.set(false);
            image.set(new byte[17 * 1024 * 1024]);
            var large = new AiImageClient(modelKeyCipher, aiEndpointPolicy, new AiImageLimits("20MB"), 10, 30);
            assertThat(large.generate(task)).hasSize(image.get().length);
            var original = new AiImageClient(modelKeyCipher, aiEndpointPolicy, new AiImageLimits("10MB"), 5, 30);
            assertThatThrownBy(() -> original.generate(task)).isInstanceOf(BizException.class);
        } finally { server.stop(0); }
        for (String invalid : new String[]{"0MB", "-1MB", "51MB", "garbage"}) {
            assertThatThrownBy(() -> new AiImageLimits(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
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
