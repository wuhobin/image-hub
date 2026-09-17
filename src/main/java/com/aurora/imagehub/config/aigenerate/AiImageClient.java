package com.aurora.imagehub.config.aigenerate;

import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.starter.webmvc.exception.BizException;
import java.io.IOException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.http.*;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.openai.api.OpenAiImageApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/** Spring AI图片调用适配；每个任务使用配置快照，超时和HTTP错误均不自动重复生图。 */
@Component
@Slf4j
public class AiImageClient {

    private final AiImageLimits aiImageLimits;

    private final ModelKeyCipher modelKeyCipher;

    private final AiEndpointPolicy aiEndpointPolicy;

    private final int timeoutSeconds;

    private final int connectTimeoutSeconds;

    private final HttpClient httpClient;

    public AiImageClient(ModelKeyCipher modelKeyCipher, AiEndpointPolicy aiEndpointPolicy, AiImageLimits aiImageLimits,
                         @Value("${image-hub.ai.timeout-seconds:300}") int timeoutSeconds,
                         @Value("${image-hub.ai.connect-timeout-seconds:30}") int connectTimeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 600) throw new IllegalArgumentException("AI timeout must be between 1 and 600 seconds");
        if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 60) {
            throw new IllegalArgumentException("AI connect timeout must be between 1 and 60 seconds");
        }
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.aiImageLimits = aiImageLimits;
        this.modelKeyCipher = modelKeyCipher;
        this.aiEndpointPolicy = aiEndpointPolicy;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 获取一张图片的字节；异常完整记录到服务端日志，用户界面仍使用业务错误提示。 */
    public byte[] generate(AiGeneration task) {
        long started = System.nanoTime();
        String stage = "准备请求";
        int[] httpStatus = {-1};
        log.info("AI 创作开始：taskId={}, modelId={}, 尺寸={}, 质量={}, 模型连接超时={} 秒, 模型响应超时={} 秒",
                task.getId(), task.getModelId(), task.getImageSize(), task.getQuality(), connectTimeoutSeconds, timeoutSeconds);
        try {
            aiEndpointPolicy.requirePublicHttps(task.getBaseUrl());
            aiEndpointPolicy.validatePath(task.getImagesPath());
            var factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
            var api = OpenAiImageApi.builder().baseUrl(task.getBaseUrl()).imagesPath(task.getImagesPath())
                    .apiKey(modelKeyCipher.decrypt(task.getApiKeyCiphertext()))
                    .restClientBuilder(RestClient.builder().requestFactory(factory)
                            .messageConverters(converters -> converters.forEach(converter -> {
                                if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                                    // 仅调整这个 AI 客户端的 Base64 字符串上限，不放宽应用的全局 JSON 解析限制。
                                    var mapper = jackson.getObjectMapper().copy();
                                    mapper.getFactory().setStreamReadConstraints(mapper.getFactory().streamReadConstraints()
                                            .rebuild().maxStringLength(aiImageLimits.getMaxResponseBytes()).build());
                                    jackson.setObjectMapper(mapper);
                                }
                            }))
                            .requestInterceptor((request, body, execution) -> {
                                ClientHttpResponse response = execution.execute(request, body);
                                httpStatus[0] = response.getStatusCode().value();
                                return limitResponse(response);
                            }))
                    .responseErrorHandler(new ResponseErrorHandler() {
                        @Override
                        public boolean hasError(ClientHttpResponse response) throws IOException {
                            return !response.getStatusCode().is2xxSuccessful();
                        }

                        @Override
                        public void handleError(URI url, org.springframework.http.HttpMethod method, ClientHttpResponse response) throws IOException {
                            throw new BizException(502, "模型服务拒绝请求（HTTP " + response.getStatusCode().value() + "），请检查配置或稍后重试");
                        }
                    }).build();
            String[] size = task.getImageSize().split("x");
            // GPT Image不接受DALL-E的style/response_format参数，保持未设置，兼容URL和Base64响应。
            var options = OpenAiImageOptions.builder().model(task.getModelCode()).N(1)
                    .width(Integer.parseInt(size[0])).height(Integer.parseInt(size[1])).quality(task.getQuality()).build();
            var model = new OpenAiImageModel(api, options, RetryTemplate.builder().maxAttempts(1).build());
            stage = "模型请求";
            long modelStarted = System.nanoTime();
            org.springframework.ai.image.ImageResponse response;
            try {
                response = model.call(new ImagePrompt(task.getPrompt()));
            } finally {
                log.info("AI 创作耗时：taskId={}, 阶段=模型请求, 耗时={} 秒",
                        task.getId(), (System.nanoTime() - modelStarted) / 1_000_000 / 1000.0);
            }
            stage = "响应校验";
            if (response == null || response.getResults() == null || response.getResults().size() != 1) {
                throw new BizException(502, "模型未返回单张图片，请检查模型配置");
            }
            var output = response.getResult().getOutput();
            byte[] bytes;
            long resultStarted = System.nanoTime();
            try {
                if (output.getB64Json() != null && !output.getB64Json().isBlank()) {
                    stage = "Base64 解码";
                    if (output.getB64Json().length() > ((aiImageLimits.getMaxBytes() + 2L) / 3) * 4) {
                        throw new BizException(502, "生成图片超过 " + aiImageLimits.getSizeLabel());
                    }
                    bytes = Base64.getDecoder().decode(output.getB64Json());
                } else {
                    stage = "下载图片";
                    bytes = download(output.getUrl());
                }
                aiImageLimits.requireSize(bytes.length);
            } finally {
                log.info("AI 创作耗时：taskId={}, 阶段={}, 耗时={} 秒",
                        task.getId(), stage, (System.nanoTime() - resultStarted) / 1_000_000 / 1000.0);
            }
            // 任务服务在持久化前统一解码校验，下载和 Base64 响应遵守同一规则。
            log.info("AI 创作已取得图片：taskId={}, httpStatus={}, 耗时={} 秒, bytes={}",
                    task.getId(), httpStatus[0], (System.nanoTime() - started) / 1_000_000 / 1000.0, bytes.length);
            return bytes;
        } catch (Exception e) {
            log.info("AI 创作失败：taskId={}, 阶段={}, httpStatus={}, 耗时={} 秒, 模型连接超时={} 秒, 模型响应超时={} 秒",
                    task.getId(), stage, httpStatus[0], (System.nanoTime() - started) / 1_000_000 / 1000.0,
                    connectTimeoutSeconds, timeoutSeconds, e);
            if (e instanceof BizException business) throw business;
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BizException(502, "生成请求超时或未取得有效结果，额度将释放，请重新提交");
        }
    }

    /** 限量读取后才交给 Jackson；不能只依赖 Content-Length，超限或读取失败立即关闭连接。 */
    private ClientHttpResponse limitResponse(ClientHttpResponse response) throws IOException {
        try {
            if (response.getHeaders().getContentLength() > aiImageLimits.getMaxResponseBytes()) {
                throw new BizException(502, "模型响应超过大小限制");
            }
            byte[] body = response.getBody().readNBytes(aiImageLimits.getMaxResponseBytes() + 1);
            if (body.length > aiImageLimits.getMaxResponseBytes()) throw new BizException(502, "模型响应超过大小限制");
            return new ClientHttpResponse() {
                @Override
                public org.springframework.http.HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }

                @Override
                public String getStatusText() throws IOException { return response.getStatusText(); }

                @Override
                public org.springframework.http.HttpHeaders getHeaders() { return response.getHeaders(); }

                @Override
                public java.io.InputStream getBody() { return new java.io.ByteArrayInputStream(body); }

                @Override
                public void close() { response.close(); }
            };
        } catch (IOException | RuntimeException e) {
            response.close();
            throw e;
        }
    }

    /** 结果URL可能含签名查询参数；访问前验证公网主机，不跟随重定向且限制下载体积。 */
    private byte[] download(String url) throws IOException {
        URI uri = URI.create(url);
        try {
            aiEndpointPolicy.requirePublicHttps(new URI(uri.getScheme(), uri.getAuthority(),
                    uri.getPath(), null, uri.getFragment()).toString());
        } catch (java.net.URISyntaxException e) {
            throw new BizException(502, "生成图片地址无效");
        }
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        try {
            if (connection.getResponseCode() != 200) throw new BizException(502, "生成图片下载失败");
            try (var input = connection.getInputStream()) {
                var output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                for (;;) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new BizException(502, "生成图片下载超时");
                    connection.setReadTimeout((int) Math.max(1, remaining / 1_000_000));
                    int count = input.read(buffer);
                    if (count < 0) return output.toByteArray();
                    aiImageLimits.requireSize((long) output.size() + count);
                    output.write(buffer, 0, count);
                }
            }
        } finally {
            connection.disconnect();
        }
    }
}
