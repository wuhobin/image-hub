package com.aurora.imagehub.service;

import com.aurora.imagehub.model.entity.TemplateTerm;
import com.aurora.imagehub.model.param.TemplateTermParam;
import com.aurora.imagehub.model.vo.TemplateTermVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 分类与标签业务；仅管理员可维护，删除前必须解除所有模板关联。
 */
public interface TemplateTermService extends IService<TemplateTerm> {

    /**
     * 返回有效词条，按管理员排序。
     */
    List<TemplateTermVO> terms();

    /**
     * 同类名称唯一，包含已删除词条；类型不可修改。
     */
    TemplateTermVO saveTerm(Long id, TemplateTermParam param);

    /**
     * 禁止删除仍被使用的词条。
     */
    void archiveTerm(long id);
}
