package com.aurora.imagehub.service.impl;

import com.aurora.imagehub.mapper.*;
import com.aurora.imagehub.model.entity.*;
import com.aurora.imagehub.model.param.*;
import com.aurora.imagehub.model.vo.*;
import com.aurora.imagehub.service.*;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.starter.mybatisplus.mybatis.PageUtils;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 模板和生成任务解耦；公开内容无缓存，图片替换保留旧定位直到云清理成功。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreationTemplateServiceImpl extends ServiceImpl<CreationTemplateMapper, CreationTemplate> implements CreationTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z][a-z0-9_]{0,31})}}");

    private final CreationTemplateMapper creationTemplateMapper;

    private final TemplateTermMapper templateTermMapper;

    private final TemplateExampleMapper templateExampleMapper;

    private final TemplateTermService templateTermService;

    private final AdminAccountService adminAccountService;

    private final AiGenerationService aiGenerationService;

    private final ImageFileService imageFileService;

    private final ObjectMapper objectMapper;

    private final PlatformTransactionManager platformTransactionManager;

    @Override
    public Page<CreationTemplateVO> browse(boolean publicOnly, int page, int pageSize, Long categoryId, Long tagId, String search, Boolean enabled) {
        if (!publicOnly) adminAccountService.currentAdmin();
        if (page < 1 || pageSize < 1 || pageSize > 50 || search == null || search.length() > 100
                || (categoryId != null && categoryId < 1) || (tagId != null && tagId < 1))
            throw new BizException(400, "筛选参数无效");
        Page<CreationTemplate> rows = creationTemplateMapper.browse(PageUtils.buildPage(page, pageSize),
                publicOnly, publicOnly ? null : enabled, categoryId, tagId, search.trim());
        Map<Long, CreationTemplateVO> converted = responses(rows.getRecords());
        return PageUtils.convert(rows, row -> converted.get(row.getId()));
    }

    @Override
    public CreationTemplateVO detail(long id, boolean publicOnly) {
        if (!publicOnly) adminAccountService.currentAdmin();
        CreationTemplate row = requireTemplate(id);
        if (publicOnly && !Boolean.TRUE.equals(row.getEnabled())) throw new BizException(404, "模板不存在或已下架");
        CreationTemplateVO result = responses(List.of(row)).get(id);
        if (result.getCategory() == null) throw new BizException(404, "模板分类已失效");
        return result;
    }

    @Override
    public CreationTemplateVO saveTemplate(Long id, CreationTemplateParam param) {
        adminAccountService.currentAdmin();
        validateFields(param);
        String fieldsJson = json(param.getFields());
        Long savedId = transaction().execute(status -> {
            // 固定顺序锁定全部词条，避免多标签交叉编辑产生死锁。
            var ids = new TreeSet<Long>(param.getTagIds());
            ids.add(param.getCategoryId());
            for (Long termId : ids) {
                TemplateTerm term = templateTermMapper.lock(termId);
                String kind = termId.equals(param.getCategoryId()) ? "CATEGORY" : "TAG";
                if (term == null || !kind.equals(term.getKind()))
                    throw new BizException(400, "分类或标签无效，请刷新后重试");
            }
            if (param.getTagIds().contains(param.getCategoryId())) throw new BizException(400, "分类不能作为标签使用");
            CreationTemplate row = id == null ? new CreationTemplate() : creationTemplateMapper.lock(id);
            if (row == null) throw new BizException(404, "模板不存在");
            row.setTitle(param.getTitle().trim());
            row.setDescription(param.getDescription().trim());
            row.setCategoryId(param.getCategoryId());
            row.setPromptPattern(param.getPromptPattern().trim());
            row.setFieldsJson(fieldsJson);
            row.setEnabled(param.getEnabled());
            row.setSortOrder(param.getSortOrder());
            if (id == null) creationTemplateMapper.insert(row);
            else creationTemplateMapper.updateById(row);
            creationTemplateMapper.clearTags(row.getId());
            for (Long tagId : new LinkedHashSet<>(param.getTagIds())) {
                if (creationTemplateMapper.restoreTag(row.getId(), tagId) == 0)
                    creationTemplateMapper.addTag(row.getId(), tagId);
            }
            return row.getId();
        });
        return detail(Objects.requireNonNull(savedId), false);
    }

    /**
     * 字段定义和占位符必须完全对应，禁止残缺语法或执行表达式；最终提示词仍受生图接口长度约束。
     */
    private void validateFields(CreationTemplateParam param) {
        // 无填写项的示例按原文使用，保留从作品导入的字面双花括号。
        if (param.getFields().isEmpty()) return;
        Set<String> keys = new HashSet<>();
        for (TemplateFieldParam field : param.getFields()) {
            if (!keys.add(field.getKey())) throw new BizException(400, "填写项标识不能重复");
        }
        Set<String> used = new HashSet<>();
        var matcher = PLACEHOLDER.matcher(param.getPromptPattern());
        while (matcher.find()) used.add(matcher.group(1));
        String rest = matcher.replaceAll("");
        if (rest.contains("{{") || rest.contains("}}") || !keys.equals(used)) {
            throw new BizException(400, "提示词占位符须使用 {{字段标识}}，并与填写项逐一对应");
        }
        var examples = param.getFields().stream().collect(Collectors.toMap(TemplateFieldParam::getKey, TemplateFieldParam::getExample));
        String rendered = PLACEHOLDER.matcher(param.getPromptPattern()).replaceAll(
                match -> java.util.regex.Matcher.quoteReplacement(examples.get(match.group(1))));
        if (rendered.length() > 4000) throw new BizException(400, "示例提示词过长，请缩短示例内容");
    }

    @Override
    public CreationTemplateVO importCreation(TemplateImportParam param) {
        adminAccountService.currentAdmin();
        SharedCreationVO source = aiGenerationService.publicCreation(param.getShareId());
        if (!Boolean.TRUE.equals(source.getPromptPublic()) || source.getPrompt() == null)
            throw new BizException(400, "只能导入已公开提示词的作品");
        ImageFile original = creationTemplateMapper.importImage(param.getShareId());
        if (original == null) throw new BizException(404, "来源作品已不可导入");
        // 复制通过受信任的存储定位读取，禁止接收客户端 URL，避免任意网络访问。
        ImageFile copied = imageFileService.copyTemplateExample(original);
        try {
            Long id = transaction().execute(status -> {
                TemplateTerm category = templateTermMapper.lock(param.getCategoryId());
                if (category == null || !"CATEGORY".equals(category.getKind()))
                    throw new BizException(400, "请选择有效分类");
                // 网络复制完成后再次确认作者仍然公开相同提示词。
                SharedCreationVO current = aiGenerationService.publicCreation(param.getShareId());
                if (!Boolean.TRUE.equals(current.getPromptPublic()) || !Objects.equals(current.getPrompt(), source.getPrompt())
                        || creationTemplateMapper.importImage(param.getShareId()) == null) {
                    throw new BizException(409, "来源作品已变化，请重新选择");
                }
                CreationTemplate row = new CreationTemplate();
                row.setTitle(param.getTitle().trim());
                row.setDescription("根据 " + source.getAuthorName() + " 的公开作品整理");
                row.setCategoryId(param.getCategoryId());
                row.setPromptPattern(source.getPrompt());
                row.setFieldsJson("[]");
                row.setEnabled(false);
                row.setSortOrder(0);
                row.setSourceShareId(source.getShareId());
                row.setSourceAuthor(source.getAuthorName());
                row.setExampleId(copied.getId());
                creationTemplateMapper.insert(row);
                persistExample(row.getId(), copied);
                return row.getId();
            });
            return detail(Objects.requireNonNull(id), false);
        } catch (RuntimeException e) {
            discardIfUncommitted(copied);
            throw e;
        }
    }

    @Override
    public CreationTemplateVO setExample(long id, MultipartFile file) {
        adminAccountService.currentAdmin();
        requireTemplate(id);
        ImageFile stored = imageFileService.storeTemplateExample(file);
        try {
            transaction().executeWithoutResult(status -> {
                CreationTemplate row = creationTemplateMapper.lock(id);
                if (row == null) throw new BizException(404, "模板不存在");
                persistExample(id, stored);
                row.setExampleId(stored.getId());
                creationTemplateMapper.updateById(row);
            });
        } catch (RuntimeException e) {
            discardIfUncommitted(stored);
            throw e;
        }
        cleanupExamples(id);
        return detail(id, false);
    }

    @Override
    public CreationTemplateVO removeExample(long id) {
        adminAccountService.currentAdmin();
        transaction().executeWithoutResult(status -> {
            CreationTemplate row = creationTemplateMapper.lock(id);
            if (row == null) throw new BizException(404, "模板不存在");
            row.setExampleId(null);
            creationTemplateMapper.updateById(row);
        });
        cleanupExamples(id);
        return detail(id, false);
    }

    /**
     * 图片定位与模板关联一起提交，用户原图片始终不受模板操作影响。
     */
    private void persistExample(long templateId, ImageFile image) {
        TemplateExample asset = new TemplateExample();
        asset.setId(image.getId());
        asset.setTemplateId(templateId);
        asset.setUrl(image.getUrl());
        asset.setStorageInfo(image.getStorageInfo());
        templateExampleMapper.insert(asset);
    }

    /**
     * 提交状态无法确认时保留云文件，不冒险删除可能已被模板使用的图片。
     */
    private void discardIfUncommitted(ImageFile image) {
        try {
            if (templateExampleMapper.selectById(image.getId()) == null) imageFileService.discardUncommitted(image);
        } catch (RuntimeException e) {
            log.warn("模板图片提交状态待确认：exampleId={}", image.getId());
        }
    }

    /**
     * 清理失败保留定位，下次替换或移除图片时重试；从不清理当前关联。
     */
    private void cleanupExamples(long id) {
        for (TemplateExample asset : templateExampleMapper.unused(id)) {
            try {
                imageFileService.discardGenerated(asset.getStorageInfo());
                templateExampleMapper.archive(asset.getId());
            } catch (RuntimeException e) {
                log.warn("模板旧示例图清理待重试：templateId={}, exampleId={}", id, asset.getId());
            }
        }
    }

    /**
     * 页面批量映射词条、图片和关联，避免 N+1；实体内部信息没有对应 VO 字段。
     */
    private Map<Long, CreationTemplateVO> responses(List<CreationTemplate> rows) {
        if (rows.isEmpty()) return Map.of();
        var terms = templateTermService.terms().stream().collect(Collectors.toMap(TemplateTermVO::getId, Function.identity()));
        var tagIds = new HashMap<Long, List<Long>>();
        for (var relation : creationTemplateMapper.tags(rows.stream().map(CreationTemplate::getId).toList())) {
            long templateId = ((Number) relation.get("template_id")).longValue();
            long termId = ((Number) relation.get("term_id")).longValue();
            tagIds.computeIfAbsent(templateId, ignored -> new ArrayList<>()).add(termId);
        }
        var exampleIds = rows.stream().map(CreationTemplate::getExampleId).filter(Objects::nonNull).toList();
        Map<String, TemplateExample> examples = exampleIds.isEmpty() ? Map.of() : templateExampleMapper.selectList(Wrappers.<TemplateExample>lambdaQuery().in(TemplateExample::getId, exampleIds))
                .stream().collect(Collectors.toMap(TemplateExample::getId, Function.identity()));
        Map<Long, CreationTemplateVO> result = new HashMap<>();
        for (CreationTemplate row : rows) {
            CreationTemplateVO vo = new CreationTemplateVO();
            BeanUtils.copyProperties(row, vo);
            vo.setCategory(terms.get(row.getCategoryId()));
            vo.setTags(tagIds.getOrDefault(row.getId(), List.of()).stream().map(terms::get).filter(Objects::nonNull)
                    .sorted(Comparator.comparing(TemplateTermVO::getSortOrder).thenComparing(TemplateTermVO::getId)).toList());
            try {
                vo.setFields(objectMapper.readValue(row.getFieldsJson(), new TypeReference<List<TemplateFieldVO>>() {
                }));
            } catch (java.io.IOException e) {
                throw new BizException(500, "模板填写项读取失败，请联系管理员");
            }
            TemplateExample example = row.getExampleId() == null ? null : examples.get(row.getExampleId());
            vo.setExampleUrl(example != null && row.getId().equals(example.getTemplateId()) ? example.getUrl() : null);
            result.put(row.getId(), vo);
        }
        return result;
    }

    /**
     * 不区分缺失和已删除记录。
     */
    private CreationTemplate requireTemplate(long id) {
        CreationTemplate row = getById(id);
        if (row == null) throw new BizException(404, "模板不存在或已下架");
        return row;
    }

    /**
     * 使用短事务提交数据库变更，云网络请求在事务外。
     */
    private TransactionTemplate transaction() {
        return new TransactionTemplate(platformTransactionManager);
    }

    /**
     * 定义仅来自已校验请求，不存可执行代码。
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new BizException(400, "模板填写项无效");
        }
    }
}
