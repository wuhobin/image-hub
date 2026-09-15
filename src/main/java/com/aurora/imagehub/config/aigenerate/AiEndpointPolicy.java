package com.aurora.imagehub.config.aigenerate;

import com.aurora.starter.webmvc.exception.BizException;
import java.net.InetAddress;
import java.net.URI;
import org.springframework.stereotype.Component;

/** 仅访问公网 HTTPS 服务；禁止凭据嵌入地址、内网地址和重定向，降低服务端请求伪造风险。 */
@Component
public class AiEndpointPolicy {

    /** 在保存配置及实际访问前验证 URL，避免把模型 Key 发往非 HTTPS 地址。 */
    public URI requirePublicHttps(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()
                        || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                        || (bytes.length == 4 && (bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127)) {
                    throw new IllegalArgumentException();
                }
            }
            return uri;
        } catch (Exception e) {
            throw new BizException(400, "模型及图片地址必须是可解析的公网 HTTPS 地址");
        }
    }

    /** 路径只允许同一主机下的绝对路径，不能覆盖已验证的主机。 */
    public void validatePath(String path) {
        if (path == null || !path.matches("/[A-Za-z0-9_./-]+") || path.contains("..") || path.startsWith("//")) {
            throw new BizException(400, "图片接口路径无效，例如 /v1/images/generations");
        }
    }
}
