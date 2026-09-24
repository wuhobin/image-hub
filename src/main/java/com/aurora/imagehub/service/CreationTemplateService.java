package com.aurora.imagehub.service;

import com.aurora.imagehub.model.entity.CreationTemplate;
import com.aurora.imagehub.model.param.CreationTemplateParam;
import com.aurora.imagehub.model.param.TemplateImportParam;
import com.aurora.imagehub.model.vo.CreationTemplateVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

/**
 * 模板业务不生图、不扣积分；导入图片为独立副本，所有维护操作复核管理员身份。
 */
public interface CreationTemplateService extends IService<CreationTemplate> {

    /**
     * 公开模式仅返回上架模板，管理模式检查管理员身份。
     */
    Page<CreationTemplateVO> browse(boolean publicOnly, int page, int pageSize, Long categoryId, Long tagId, String search, Boolean enabled);

    /**
     * 不可见模板统一返回 404。
     */
    CreationTemplateVO detail(long id, boolean publicOnly);

    /**
     * 校验所有占位符和填写项，保存标签关联。
     */
    CreationTemplateVO saveTemplate(Long id, CreationTemplateParam param);

    /**
     * 仅导入公开提示词的公开成功作品，复制后保存为草稿。
     */
    CreationTemplateVO importCreation(TemplateImportParam param);

    /**
     * 示例图片独立存储，不写用户图库，不消耗用户积分。
     */
    CreationTemplateVO setExample(long id, MultipartFile file);

    /**
     * 移除示例图，保留文字模板。
     */
    CreationTemplateVO removeExample(long id);
}
