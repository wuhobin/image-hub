package com.aurora.imagehub.controller;

import com.aurora.imagehub.model.vo.*;
import com.aurora.imagehub.service.*;
import com.aurora.starter.webmvc.domain.response.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 游客可浏览上架模板和词条；填写和使用模板不创建生成任务。
 */
@RestController
@RequestMapping("/api/app/public/templates")
@RequiredArgsConstructor
public class PublicTemplateController {

    private final CreationTemplateService creationTemplateService;

    private final TemplateTermService templateTermService;

    /**
     * 上下架即时生效，禁止浏览器和代理复用旧响应。
     */
    @ModelAttribute
    public void disableCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * 分类、标签、关键词组合筛选，使用平台分页。
     */
    @GetMapping
    public Result<Page<CreationTemplateVO>> list(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "12") int pageSize, @RequestParam(required = false) Long categoryId,
                                                 @RequestParam(required = false) Long tagId, @RequestParam(defaultValue = "") String search) {
        return Result.data(creationTemplateService.browse(true, page, pageSize, categoryId, tagId, search, null));
    }

    /**
     * 公开词表只返回名称和排序。
     */
    @GetMapping("/terms")
    public Result<List<TemplateTermVO>> terms() {
        return Result.data(templateTermService.terms());
    }

    /**
     * 使用前再次读取，已下架模板不能继续通过详情接口使用。
     */
    @GetMapping("/{id}")
    public Result<CreationTemplateVO> detail(@PathVariable long id) {
        return Result.data(creationTemplateService.detail(id, true));
    }
}
