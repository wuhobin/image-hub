package com.aurora.imagehub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.model.vo.ImageListVO;
import com.aurora.imagehub.model.vo.ImageVO;
import com.aurora.imagehub.model.vo.UploadQuotaVO;
import org.springframework.web.multipart.MultipartFile;

/** ImageFile 实体相关的上传、记录查询和删除服务。userId 必须来自可信登录态。 */
public interface ImageFileService extends IService<ImageFile> {
    /**
     * 管理员模板示例独立存储，调用方负责记录定位；不入用户图库或计费。
     */
    ImageFile storeTemplateExample(MultipartFile file);

    /**
     * 仅复制已通过公开资格检查的图片；从可信存储定位读取并限制大小。
     */
    ImageFile copyTemplateExample(ImageFile source);

    /** 当前账号永久积分；历史上传不计费，删除不返还。 */
    UploadQuotaVO quota(long userId);
    /** 校验并上传单张图片，记录入库失败时补偿删除云端文件。 */
    ImageVO upload(long userId, MultipartFile file);

    /**
     * 免费头像复用真实图片校验，单独存入 avatars 目录；调用方负责头像与图片记录的同事务写入。
     */
    ImageFile storeAvatar(long userId, MultipartFile file);

    /** 校验单张 JPG/PNG/WEBP 参考图并返回真实 MIME，不上传云端、不扣额、不入库。 */
    String validateReference(MultipartFile file);

    /** 分页图片及全部匹配图片的总大小；sort 为 desc 最新优先、asc 最早优先。 */
    ImageListVO list(long userId, String search, String type, String sourceType, int page, int pageSize, String sort);

    /** 校验并上传生成结果，上传前由回调持久化对象定位；回调失败则不上传，此方法不扣额、不写图片表。 */
    ImageFile storeGenerated(long userId, String imageId, byte[] bytes, java.util.function.Consumer<String> beforeUpload);

    /** 删除持久化定位的未入库对象；失败必须抛出异常，让任务保留记录继续补偿。 */
    void discardGenerated(String storageInfo);

    /** 仅供未入库云文件补偿，业务删除仍使用含归属校验的delete。 */
    void discardUncommitted(ImageFile image);

    /** 校验图片归属，先删除云端文件，再逻辑删除数据库记录；支持失败后重试。 */
    void delete(long userId, String id);
}
