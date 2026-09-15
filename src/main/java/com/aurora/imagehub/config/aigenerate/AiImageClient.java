package com.aurora.imagehub.config.aigenerate;

import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.starter.webmvc.exception.BizException;
import java.io.IOException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.http.*;
import java.time.Duration;
import java.util.Base64;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.openai.api.OpenAiImageApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/** Spring AI图片调用适配；每个任务使用配置快照，超时和HTTP错误均不自动重复生图。 */
@Component
public class AiImageClient {

    private static final int MAX_BYTES = 10 * 1024 * 1024;

    private final ModelKeyCipher modelKeyCipher;

    private final AiEndpointPolicy aiEndpointPolicy;

    private final int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public AiImageClient(ModelKeyCipher modelKeyCipher, AiEndpointPolicy aiEndpointPolicy,
                         @Value("${image-hub.ai.timeout-seconds:180}") int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 600) throw new IllegalArgumentException("AI timeout must be between 1 and 600 seconds");
        this.modelKeyCipher = modelKeyCipher;
        this.aiEndpointPolicy = aiEndpointPolicy;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 获取一张图片的字节；不把供应商原始报错、签名地址或敏感响应传播到日志和用户界面。 */
    public byte[] generate(AiGeneration task) {
        try {
            aiEndpointPolicy.requirePublicHttps(task.getBaseUrl());
            aiEndpointPolicy.validatePath(task.getImagesPath());
            var factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
            var api = OpenAiImageApi.builder().baseUrl(task.getBaseUrl()).imagesPath(task.getImagesPath())
                    .apiKey(modelKeyCipher.decrypt(task.getApiKeyCiphertext()))
                    .restClientBuilder(RestClient.builder().requestFactory(factory))
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
            var response = model.call(new ImagePrompt(task.getPrompt()));
            if (response == null || response.getResults() == null || response.getResults().size() != 1) {
                throw new BizException(502, "模型未返回单张图片，请检查模型配置");
            }
            var output = response.getResult().getOutput();
            byte[] bytes;
            if (output.getB64Json() != null && !output.getB64Json().isBlank()) {
                if (output.getB64Json().length() > ((MAX_BYTES + 2L) / 3) * 4) throw new BizException(502, "生成图片超过10MB");
                bytes = Base64.getDecoder().decode(output.getB64Json());
            } else {
                bytes = download(output.getUrl());
            }
            // 提前检查图片容器和体积，避免把任意二进制持久化为可重试结果。
            new GeneratedImageFile(bytes);
            return bytes;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BizException(502, "生成请求超时或未取得有效结果，额度将释放，请重新提交");
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
                    if (output.size() + count > MAX_BYTES) throw new BizException(502, "生成图片超过10MB");
                    output.write(buffer, 0, count);
                }
            }
        } finally {
            connection.disconnect();
        }
    }
}
