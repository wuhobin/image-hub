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

    public GeneratedImageFile(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 10 * 1024 * 1024) {
            throw new BizException(400, "生成图片为空或超过10MB");
        }
        this.bytes = bytes;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BizException(400, "生成结果不是有效图片");
            var reader = readers.next();
            try { format = reader.getFormatName().toLowerCase(Locale.ROOT); }
            finally { reader.dispose(); }
        } catch (IOException e) {
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
