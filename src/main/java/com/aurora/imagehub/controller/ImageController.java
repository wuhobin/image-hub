package com.aurora.imagehub.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.aurora.imagehub.service.ImageFileService;
import com.aurora.imagehub.model.vo.ImageVO;
import com.aurora.imagehub.model.vo.ImageStatsVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aurora.starter.webmvc.domain.response.Result;
import com.aurora.starter.webmvc.exception.BizException;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** 图片 HTTP 入口；从登录态取得用户 ID，避免客户端指定其他用户的图片归属。 */
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
@Tag(name = "图片管理")
public class ImageController {
    private final ImageFileService imageFileService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ImageVO> upload(MultipartHttpServletRequest request) {
        // 前端批量上传拆成单文件请求，以支持独立失败重试和真实进度。
        long count = request.getMultiFileMap().values().stream().mapToLong(java.util.List::size).sum();
        if (count != 1 || request.getFile("file") == null) {
            throw new BizException(400, "每个请求需包含一个 file 图片文件");
        }
        return Result.data(imageFileService.upload(StpUtil.getLoginIdAsLong(), request.getFile("file")));
    }

    @GetMapping
    public Result<Page<ImageVO>> list(@RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String type, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int pageSize) {
        return Result.data(imageFileService.list(StpUtil.getLoginIdAsLong(), search, type, page, pageSize));
    }

    @GetMapping("/stats")
    public Result<ImageStatsVO> stats() {
        return Result.data(imageFileService.stats(StpUtil.getLoginIdAsLong()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        imageFileService.delete(StpUtil.getLoginIdAsLong(), id);
        return Result.success();
    }
}
