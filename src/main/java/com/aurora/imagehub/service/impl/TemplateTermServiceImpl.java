package com.aurora.imagehub.service.impl;

import com.aurora.imagehub.mapper.TemplateTermMapper;
import com.aurora.imagehub.model.entity.TemplateTerm;
import com.aurora.imagehub.model.param.TemplateTermParam;
import com.aurora.imagehub.model.vo.TemplateTermVO;
import com.aurora.imagehub.service.TemplateTermService;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 受控词表集中去重，使用行锁协调模板保存与词条删除。
 */
@Service
@RequiredArgsConstructor
public class TemplateTermServiceImpl extends ServiceImpl<TemplateTermMapper, TemplateTerm> implements TemplateTermService {

    private final TemplateTermMapper templateTermMapper;

    private final AdminAccountService adminAccountService;

    @Override
    public List<TemplateTermVO> terms() {
        return list(Wrappers.<TemplateTerm>lambdaQuery().orderByAsc(TemplateTerm::getSortOrder, TemplateTerm::getId))
                .stream().map(this::response).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TemplateTermVO saveTerm(Long id, TemplateTermParam param) {
        adminAccountService.currentAdmin();
        TemplateTerm term = id == null ? new TemplateTerm() : templateTermMapper.lock(id);
        if (term == null) throw new BizException(404, "分类或标签不存在");
        if (id != null && !term.getKind().equals(param.getKind()))
            throw new BizException(400, "不能改变分类或标签的类型");
        String name = param.getName().trim();
        if (templateTermMapper.occupied(param.getKind(), name, id) > 0) {
            throw new BizException(409, "名称已被使用，包括已删除词条，请选择已有词条或使用新名称");
        }
        term.setKind(param.getKind());
        term.setName(name);
        term.setSortOrder(param.getSortOrder());
        try {
            if (id == null) templateTermMapper.insert(term);
            else templateTermMapper.updateById(term);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "名称已被使用，请刷新后选择已有词条");
        }
        return response(getById(term.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archiveTerm(long id) {
        adminAccountService.currentAdmin();
        if (templateTermMapper.lock(id) == null) throw new BizException(404, "分类或标签不存在");
        if (templateTermMapper.usages(id) > 0) throw new BizException(409, "仍有模板使用该词条，请先调整模板关联");
        templateTermMapper.archive(id);
    }

    /**
     * 仅复制公开字段。
     */
    private TemplateTermVO response(TemplateTerm term) {
        TemplateTermVO result = new TemplateTermVO();
        BeanUtils.copyProperties(term, result);
        return result;
    }
}
