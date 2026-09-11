package com.aurora.imagehub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.model.vo.ImageStatsVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aurora.imagehub.model.vo.ImageVO;
import org.springframework.web.multipart.MultipartFile;

/** ImageFile 实体相关的上传、记录查询和删除服务。userId 必须来自可信登录态。 */
public interface ImageFileService extends IService<ImageFile> {
    /** 校验并上传单张图片，记录入库失败时补偿删除云端文件。 */
    ImageVO upload(long userId, MultipartFile file);

    /** 使用平台 MyBatis-Plus 分页查询指定用户的图片。 */
    Page<ImageVO> list(long userId, String search, String type, int page, int pageSize);

    /** 当前用户全部图片的数量和大小，不受列表筛选及分页影响。 */
    ImageStatsVO stats(long userId);

    /** 校验图片归属，先删除云端文件，再删除数据库记录；支持失败后重试。 */
    void delete(long userId, String id);
}
