package com.aurora.imagehub.config.aigenerate;

import com.aurora.starter.webmvc.exception.BizException;
import java.io.*;
import java.nio.file.Files;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.web.multipart.MultipartFile;

/** 将已取得的生成图片交给现有上传校验，不引入生产环境的测试依赖。 */
public final class GeneratedImageFile implements MultipartFile {

    private final byte[] bytes;

    private final String format;

    /** 入库前验证支持的容器及首帧内容；先限制像素再解码，避免损坏数据反复进入保存重试。 */
    public GeneratedImageFile(byte[] bytes, AiImageLimits aiImageLimits) {
        aiImageLimits.requireSize(bytes == null ? 0 : bytes.length);
        this.bytes = bytes;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BizException(400, "生成结果不是有效图片");
            var reader = readers.next();
            try {
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!java.util.Set.of("png", "jpg", "jpeg", "webp", "gif").contains(format)) {
                    throw new BizException(400, "生成图片格式不支持");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                // 最大支持 Gemini 4K 的 12288×1536 长图；仍在完整解码前限制像素，防止异常图片耗尽内存。
                if (width <= 0 || height <= 0 || (long) width * height > 18_874_368) {
                    throw new BizException(400, "生成图片尺寸无效或超过像素上限");
                }
                reader.addIIOReadWarningListener((source, warning) -> { throw new IllegalArgumentException("Invalid image"); });
                reader.read(0);
            }
            finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) {
            throw new BizException(400, "无法读取生成图片");
        }
    }

    @Override
    public String getName() { return "file"; }

    @Override
    public String getOriginalFilename() { return "AI创作." + format; }

    @Override
    public String getContentType() { return "image/" + (format.equals("jpg") ? "jpeg" : format); }

    @Override
    public boolean isEmpty() { return bytes.length == 0; }

    @Override
    public long getSize() { return bytes.length; }

    @Override
    public byte[] getBytes() { return bytes; }

    @Override
    public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }

    @Override
    public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
}
