package com.aurora.imagehub.config.aigenerate;

import com.aurora.starter.webmvc.exception.BizException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-GCM 加密模型 Key；主密钥由环境提供，绝不与业务密文一起存库或打印。 */
@Component
public class ModelKeyCipher {

    private final String masterKey;

    private final SecureRandom secureRandom = new SecureRandom();

    public ModelKeyCipher(@Value("${image-hub.ai.encryption-key:}") String masterKey) {
        this.masterKey = masterKey;
    }

    /** 每条密文使用独立随机 nonce，数据库中相同 Key 也不产生相同密文。 */
    public String encrypt(String value) {
        byte[] nonce = new byte[12];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new BizException(503, "模型密钥加密不可用，请联系管理员");
        }
    }

    /** 认证解密失败只返回固定错误，不携带密文、Key 或供应商响应。 */
    public String decrypt(String value) {
        SecretKeySpec key = key();
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(value));
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new BizException(503, "模型密钥无法解密，请联系管理员");
        }
    }

    private SecretKeySpec key() {
        try {
            byte[] decoded = Base64.getDecoder().decode(masterKey);
            if (decoded.length == 32) return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException ignored) {
            // 主密钥配置错误不能回退为明文存储。
        }
        throw new BizException(503, "请先配置 AI_MODEL_ENCRYPTION_KEY（32字节密钥的Base64）");
    }
}
