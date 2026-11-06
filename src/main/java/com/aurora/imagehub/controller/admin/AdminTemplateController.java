package com.aurora.imagehub.controller.admin;

import com.aurora.imagehub.model.param.*;
import com.aurora.imagehub.model.vo.*;
import com.aurora.imagehub.service.*;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.starter.webmvc.domain.response.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 模板管理复用独立管理员身份，文件操作不占用普通用户积分。
 */
@RestController
@RequestMapping("/api/admin/templates")
@RequiredArgsConstructor
public class AdminTemplateController {

    private final CreationTemplateService creationTemplateService;

    private final TemplateTermService templateTermService;

    private final AdminAccountService adminAccountService;

    /**
     * 管理分页可筛选草稿和已上架模板。
     */
    @GetMapping
    public Result<Page<CreationTemplateVO>> list(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) Long categoryId,
                                                 @RequestParam(required = false) Long tagId, @RequestParam(defaultValue = "") String search,
                                                 @RequestParam(required = false) Boolean enabled) {
        return Result.data(creationTemplateService.browse(false, page, pageSize, categoryId, tagId, search, enabled));
    }

    /**
     * 查询管理详情。
     */
    @GetMapping("/{id}")
    public Result<CreationTemplateVO> detail(@PathVariable long id) {
        return Result.data(creationTemplateService.detail(id, false));
    }

    /**
     * 新建模板并配置封面 URL。
     */
    @PostMapping
    public Result<CreationTemplateVO> create(@Valid @RequestBody CreationTemplateParam param) {
        return Result.data(creationTemplateService.saveTemplate(null, param));
    }

    /**
     * 编辑正文、封面 URL、分类、标签、排序及上架状态。
     */
    @PutMapping("/{id}")
    public Result<CreationTemplateVO> update(@PathVariable long id, @Valid @RequestBody CreationTemplateParam param) {
        return Result.data(creationTemplateService.saveTemplate(id, param));
    }

    /**
     * 导入公开作品为独立草稿，返回后继续配置填写项。
     */
    @PostMapping("/import")
    public Result<CreationTemplateVO> importCreation(@Valid @RequestBody TemplateImportParam param) {
        return Result.data(creationTemplateService.importCreation(param));
    }

    /**
     * 兼容清理历史示例图片，移除后保留模板正文与封面 URL。
     */
    @DeleteMapping("/{id}/image")
    public Result<CreationTemplateVO> removeImage(@PathVariable long id) {
        return Result.data(creationTemplateService.removeExample(id));
    }

    /**
     * 管理词表仍检查管理员账号是否有效。
     */
    @GetMapping("/terms")
    public Result<List<TemplateTermVO>> terms() {
        adminAccountService.currentAdmin();
        return Result.data(templateTermService.terms());
    }

    /**
     * 同类名称去重，由数据库约束处理并发创建。
     */
    @PostMapping("/terms")
    public Result<TemplateTermVO> createTerm(@Valid @RequestBody TemplateTermParam param) {
        return Result.data(templateTermService.saveTerm(null, param));
    }

    /**
     * 修改名称与显示顺序，不改变分类或标签类型。
     */
    @PutMapping("/terms/{id}")
    public Result<TemplateTermVO> updateTerm(@PathVariable long id, @Valid @RequestBody TemplateTermParam param) {
        return Result.data(templateTermService.saveTerm(id, param));
    }

    /**
     * 引用中的词条不能删除。
     */
    @DeleteMapping("/terms/{id}")
    public Result<Void> deleteTerm(@PathVariable long id) {
        templateTermService.archiveTerm(id);
        return Result.success();
    }
}
