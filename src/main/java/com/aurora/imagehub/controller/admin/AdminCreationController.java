package com.aurora.imagehub.controller.admin;

import com.aurora.imagehub.model.vo.SharedCreationVO;
import com.aurora.imagehub.service.AiGenerationService;
import com.aurora.starter.webmvc.domain.response.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 分享管理仅提供分页与下架；有效管理员身份在服务内复核，不删除用户原作品。
 */
@RestController
@RequestMapping("/api/admin/creations")
@RequiredArgsConstructor
public class AdminCreationController {

    private final AiGenerationService aiGenerationService;

    /**
     * 展示已公开和已下架作品，隐藏提示词仍遵循作者设置。
     */
    @GetMapping
    public Result<Page<SharedCreationVO>> list(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int pageSize) {
        return Result.data(aiGenerationService.managedCreations(page, pageSize));
    }

    /**
     * 下架后停止公开展示，作者不得通过重新发布解除下架。
     */
    @PostMapping("/{shareId}/block")
    public Result<Void> block(@PathVariable String shareId) {
        aiGenerationService.blockShare(shareId);
        return Result.success();
    }
}
