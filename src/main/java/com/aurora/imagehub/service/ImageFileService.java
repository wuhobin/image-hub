package com.aurora.imagehub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.model.vo.ImageListVO;
import com.aurora.imagehub.model.vo.ImageVO;
import com.aurora.imagehub.model.vo.UploadQuotaVO;
import org.springframework.web.multipart.MultipartFile;

/** ImageFile 实体相关的上传、记录查询和删除服务。userId 必须来自可信登录态。 */
public interface ImageFileService extends IService<ImageFile> {
    /** 当前账号永久额度；历史上传不计费，删除不返还。 */
    UploadQuotaVO quota(long userId);
    /** 校验并上传单张图片，记录入库失败时补偿删除云端文件。 */
    ImageVO upload(long userId, MultipartFile file);

    /** 分页图片及全部匹配图片的总大小；sort 为 desc 最新优先、asc 最早优先。 */
    ImageListVO list(long userId, String search, String type, String sourceType, int page, int pageSize, String sort);

    /** 生成结果通过同一内容校验和云存储流程，返回待入库实体；此方法不扣额、不入库。 */
    ImageFile storeGenerated(long userId, String imageId, byte[] bytes);

    /** 仅供未入库云文件补偿，业务删除仍使用含归属校验的delete。 */
    void discardUncommitted(ImageFile image);

    /** 校验图片归属，先删除云端文件，再逻辑删除数据库记录；支持失败后重试。 */
    void delete(long userId, String id);
}
