package com.aurora.imagehub.service.impl;

import com.aurora.imagehub.mapper.ImageMapper;
import com.aurora.imagehub.service.ImageFileService;
import com.aurora.imagehub.cache.UploadQuotaCache;
import com.aurora.imagehub.model.vo.UploadQuotaVO;
import com.aurora.imagehub.model.bo.ImageDimensionsBO;
import com.aurora.imagehub.config.aigenerate.GeneratedImageFile;
import com.aurora.imagehub.model.vo.ImageListVO;
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

    private final UploadQuotaCache uploadQuotaCache;

    @Override
    public UploadQuotaVO quota(long userId) {
        return uploadQuotaCache.get(userId);
    }

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
        return uploadQuotaCache.consume(userId, id, () -> uploadValidated(userId, file, mime, dimensions, id));
    }

    /** 普通上传先保存云文件，再写库；回读失败不撤销已经成功保存的图片。 */
    private ImageVO uploadValidated(long userId, MultipartFile file, String mime, ImageDimensionsBO dimensions, String id) {
        ImageFile image = storeValidated(userId, file, mime, dimensions, id, "UPLOAD");
        try {
            uploadQuotaCache.checkOwnership(userId);
            imageMapper.insert(image);
        } catch (Exception e) {
            discardUncommitted(image);
            throw new BizException(500, "上传记录保存失败，请稍后重试");
        }
        ImageFile saved;
        try {
            saved = imageMapper.findOwned(userId, id);
        } catch (RuntimeException e) {
            throw new BizException(500, "图片已保存，但读取记录失败，请刷新上传记录");
        }
        if (saved == null) throw new BizException(500, "图片已保存，但读取记录失败，请刷新上传记录");
        return ImageVO.from(saved);
    }

    /** AI图片复用内容检测及元数据构建，事务与额度结算由任务服务统一完成。 */
    @Override
    public ImageFile storeGenerated(long userId, String imageId, byte[] bytes) {
        MultipartFile file = new GeneratedImageFile(bytes);
        String mime;
        try {
            mime = fileUploadValidator.validate(file);
        } catch (FileValidationException e) {
            throw new BizException(400, "生成图片无效或超过10MB");
        }
        if (!TYPES.containsKey(mime)) throw new BizException(400, "生成图片格式不支持");
        return storeValidated(userId, file, mime, dimensions(file), imageId, "AI");
    }

    /** 使用独立对象名，重试补偿仅删除本次上传，不能误删另一保存尝试的文件。 */
    private ImageFile storeValidated(long userId, MultipartFile file, String mime, ImageDimensionsBO dimensions,
                                    String id, String sourceType) {
        String filename = UUID.randomUUID() + "." + TYPES.get(mime).toLowerCase(Locale.ROOT);
        FileInfo stored = ossTemplate.getFileStorageService().of(file).setPath("images/" + userId + "/")
                .setSaveFilename(filename).setContentType(mime).upload();
        if (stored == null) throw new BizException(502, "图片上传失败，请重试");
        ImageFile image = new ImageFile();
        image.setId(id);
        image.setUserId(userId);
        String original = java.util.Objects.toString(file.getOriginalFilename(), filename).replace('\\', '/');
        image.setName(original.substring(original.lastIndexOf('/') + 1));
        image.setUrl(stored.getUrl());
        image.setType(TYPES.get(mime));
        image.setSourceType(sourceType);
        image.setSize(file.getSize());
        image.setWidth(dimensions.getWidth());
        image.setHeight(dimensions.getHeight());
        image.setQuotaCharged(1);
        try {
            // 存储定位保留ISO毫秒精度，与对外时间格式解耦。
            image.setStorageInfo(objectMapper.writer(new StdDateFormat()).writeValueAsString(stored));
        } catch (IOException e) {
            try { if (!ossTemplate.delete(stored)) log.warn("Cloud cleanup required: imageId={}", id); }
            catch (RuntimeException cleanup) { log.warn("Cloud cleanup required: imageId={}", id); }
            throw new BizException(500, "图片存储信息保存失败");
        }
        return image;
    }

    /** 入库确认回滚后才能调用；补偿失败保留日志中的图片ID供排查，不输出敏感元数据。 */
    @Override
    public void discardUncommitted(ImageFile image) {
        try {
            if (!ossTemplate.delete(readStorageInfo(image.getStorageInfo()))) {
                log.warn("Cloud cleanup required: imageId={}", image.getId());
            }
        } catch (Exception e) {
            log.warn("Cloud cleanup required: imageId={}", image.getId());
        }
    }

    @Override
    public ImageListVO list(long userId, String search, String type, String sourceType, int page, int pageSize, String sort) {
        search = normalizeSearch(search);
        type = normalizeType(type);
        if (sourceType == null) sourceType = "";
        if (!java.util.Set.of("", "UPLOAD", "AI").contains(sourceType)) throw new BizException(400, "图片来源无效");
        if (page < 1 || pageSize < 1 || pageSize > 100
                || (!"asc".equals(sort) && !"desc".equals(sort))) {
            throw new BizException(400, "查询参数无效");
        }
        // 平台分页拦截器自动执行 count 和分页 SQL，转换时保留原生分页元信息。
        Page<ImageFile> result = imageMapper.list(PageUtils.buildPage(page, pageSize), userId, search, type, sourceType, "asc".equals(sort));
        return new ImageListVO(PageUtils.convert(result, ImageVO::from), imageMapper.sumBytes(userId, search, type, sourceType));
    }

    private String normalizeSearch(String search) {
        String normalized = search == null ? "" : search.trim();
        if (normalized.length() > 255) throw new BizException(400, "查询参数无效");
        return normalized;
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (!normalized.isEmpty() && !TYPES.containsValue(normalized)) throw new BizException(400, "查询参数无效");
        return normalized;
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
