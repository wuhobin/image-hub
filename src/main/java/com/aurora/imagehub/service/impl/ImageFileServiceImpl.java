package com.aurora.imagehub.service.impl;

import com.aurora.imagehub.mapper.ImageMapper;
import com.aurora.imagehub.service.ImageFileService;
import com.aurora.imagehub.model.bo.ImageDimensionsBO;
import com.aurora.imagehub.model.vo.ImageStatsVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.model.vo.ImageVO;
import com.aurora.starter.oss.template.OssTemplate;
import com.aurora.starter.oss.validation.FileUploadValidator;
import com.aurora.starter.oss.exception.FileValidationException;
import com.aurora.starter.webmvc.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.aurora.starter.mybatisplus.mybatis.PageUtils;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 图片实体服务实现，负责内容校验、云端文件与数据库记录的一致性及用户隔离。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageFileServiceImpl extends ServiceImpl<ImageMapper, ImageFile> implements ImageFileService {
    private static final Map<String, String> TYPES = Map.of(
            "image/jpeg", "JPG", "image/png", "PNG", "image/webp", "WEBP", "image/gif", "GIF");
    private final ImageMapper imageMapper;
    private final OssTemplate ossTemplate;
    private final FileUploadValidator fileUploadValidator;
    private final ObjectMapper objectMapper;

    @Override
    public ImageVO upload(long userId, MultipartFile file) {
        String mime;
        try {
            mime = fileUploadValidator.validate(file);
        } catch (FileValidationException e) {
            throw new BizException(400, "图片校验失败：仅支持真实的 JPG、PNG、WEBP、GIF，单张最大 10 MB");
        }
        if (!TYPES.containsKey(mime)) throw new BizException(400, "不支持的图片格式");
        ImageDimensionsBO dimensions = dimensions(file);
        String id = UUID.randomUUID().toString();
        String path = "images/" + userId + "/";
        String filename = id + "." + TYPES.get(mime).toLowerCase(Locale.ROOT);
        FileInfo stored = ossTemplate.getFileStorageService().of(file).setPath(path)
                .setSaveFilename(filename).setContentType(mime).upload();
        if (stored == null) throw new BizException(502, "图片上传失败，请重试");

        ImageFile image = new ImageFile();
        image.setId(id);
        image.setUserId(userId);
        String original = file.getOriginalFilename().replace('\\', '/');
        image.setName(original.substring(original.lastIndexOf('/') + 1));
        image.setUrl(stored.getUrl());
        image.setType(TYPES.get(mime));
        image.setSize(file.getSize());
        image.setWidth(dimensions.getWidth());
        image.setHeight(dimensions.getHeight());
        try {
            // 保存完整存储定位信息；若记录入库失败，补偿删除刚上传的云端文件。
            // 持久化元数据固定使用 ISO 时间并保留毫秒，不受接口展示格式影响。
            image.setStorageInfo(objectMapper.writer(new StdDateFormat()).writeValueAsString(stored));
            imageMapper.insert(image);
        } catch (Exception e) {
            try {
                if (!ossTemplate.delete(stored)) log.error("Upload rollback requires cleanup: imageId={}", id);
            } catch (Exception cleanup) {
                log.error("Upload rollback requires cleanup: imageId={}", id, cleanup);
            }
            throw new BizException(500, "上传记录保存失败，请稍后重试", e);
        }
        // 时间由数据库生成，重新读取后再返回；入库已成功，回读失败不能触发云端补偿删除。
        ImageFile saved;
        try {
            saved = imageMapper.findOwned(userId, id);
        } catch (RuntimeException e) {
            throw new BizException(500, "图片已保存，但读取记录失败，请刷新上传记录", e);
        }
        if (saved == null) throw new BizException(500, "图片已保存，但读取记录失败，请刷新上传记录");
        return ImageVO.from(saved);
    }

    @Override
    public Page<ImageVO> list(long userId, String search, String type, int page, int pageSize) {
        search = search == null ? "" : search.trim();
        type = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (search.length() > 255 || page < 1 || pageSize < 1 || pageSize > 100
                || (!type.isEmpty() && !TYPES.containsValue(type))) {
            throw new BizException(400, "查询参数无效");
        }
        // 平台分页拦截器自动执行 count 和分页 SQL，转换时保留原生分页元信息。
        Page<ImageFile> result = imageMapper.list(PageUtils.buildPage(page, pageSize), userId, search, type);
        return PageUtils.convert(result, ImageVO::from);
    }

    @Override
    public ImageStatsVO stats(long userId) {
        return imageMapper.stats(userId);
    }

    @Override
    public void delete(long userId, String id) {
        ImageFile image = imageMapper.findOwned(userId, id);
        if (image == null) throw new BizException(404, "图片不存在或已删除");
        FileInfo stored;
        try {
            stored = readStorageInfo(image.getStorageInfo());
        } catch (IOException e) {
            throw new BizException(500, "文件存储信息异常，请联系管理员", e);
        }
        // 云端删除失败时保留记录；若上次仅数据库删除失败，允许在云端文件已不存在时重试。
        if (ossTemplate.getFileStorageService().exists(stored) && !ossTemplate.delete(stored)) {
            throw new BizException(502, "存储文件删除失败，请重试");
        }
        // 云端删除成功后仅标记记录，不再物理删除数据库行。
        imageMapper.deleteOwned(userId, id);
    }

    /** 兼容原有 ISO 元数据，以及统一接口格式期间保存的北京时间；不修改全局 Mapper。 */
    private FileInfo readStorageInfo(String storageInfo) throws IOException {
        try {
            return objectMapper.readerFor(FileInfo.class)
                    .with(objectMapper.getDeserializationConfig().with(new StdDateFormat()))
                    .readValue(storageInfo);
        } catch (InvalidFormatException e) {
            var legacyDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
            legacyDateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            legacyDateFormat.setLenient(false);
            return objectMapper.readerFor(FileInfo.class)
                    .with(objectMapper.getDeserializationConfig().with(legacyDateFormat))
                    .readValue(storageInfo);
        }
    }

    /** 仅读取首帧尺寸，避免为校验尺寸解码完整图片。 */
    private ImageDimensionsBO dimensions(MultipartFile file) {
        try (var stream = new MemoryCacheImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new BizException(400, "无法识别图片内容");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw new BizException(400, "图片尺寸无效");
                return new ImageDimensionsBO(width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new BizException(400, "图片内容损坏或格式无效");
        }
    }
}
