package com.aurora.imagehub.config.aigenerate;

import com.aurora.starter.webmvc.exception.BizException;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/** AI 图片独立的体积限制；模型响应、下载和保存共用，不影响普通上传。 */
@Component
@Getter
public class AiImageLimits {

    private final int maxBytes;

    private final int maxResponseBytes;

    private final String sizeLabel;

    /** 启动时拒绝非法或过大的配置，避免响应缓冲溢出；单位遵循 Spring DataSize。 */
    public AiImageLimits(@Value("${image-hub.ai.max-image-size:10MB}") String maxImageSize) {
        long bytes = DataSize.parse(maxImageSize).toBytes();
        if (bytes < 1 || bytes > DataSize.ofMegabytes(50).toBytes()) {
            throw new IllegalArgumentException("image-hub.ai.max-image-size must be positive and no greater than 50MB");
        }
        maxBytes = (int) bytes;
        // Base64 的膨胀长度加 1 MiB JSON 包络，在 Jackson 分配对象前拦截超限响应。
        maxResponseBytes = (int) (((bytes + 2) / 3) * 4 + 1024 * 1024);
        sizeLabel = maxImageSize;
    }

    /** 按实际解码或下载后的字节校验；不能仅凭 Base64 长度判断填充边界。 */
    public void requireSize(long bytes) {
        if (bytes <= 0) throw new BizException(502, "生成图片为空");
        if (bytes > maxBytes) throw new BizException(502, "生成图片超过 " + sizeLabel);
    }
}
