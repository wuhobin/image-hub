package com.aurora.imagehub.controller;

import com.aurora.imagehub.model.vo.SharedCreationVO;
import com.aurora.imagehub.service.AiGenerationService;
import com.aurora.starter.webmvc.domain.response.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 游客只读作品入口；禁止缓存含可撤销内容的响应，写入仍走受保护的用户接口。
 */
@RestController
@RequestMapping("/api/app/public/creations")
@RequiredArgsConstructor
public class PublicCreationController {

    private final AiGenerationService aiGenerationService;

    /**
     * 每次读取重新校验公开状态，浏览器和代理不得缓存旧提示词及分享信息。
     */
    @ModelAttribute
    public void disableCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * 按发布时间倒序展示公开作品，分页上限由服务校验。
     */
    @GetMapping
    public Result<Page<SharedCreationVO>> list(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "12") int pageSize) {
        return Result.data(aiGenerationService.publicCreations(page, pageSize));
    }

    /**
     * 使用独立分享编号查询，不能通过任务编号访问未分享作品。
     */
    @GetMapping("/{shareId}")
    public Result<SharedCreationVO> detail(@PathVariable String shareId) {
        return Result.data(aiGenerationService.publicCreation(shareId));
    }
}
