package com.aurora.imagehub.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.aurora.imagehub.model.param.GenerationParam;
import com.aurora.imagehub.model.vo.AiModelVO;
import com.aurora.imagehub.model.vo.GenerationVO;
import com.aurora.imagehub.service.AiGenerationService;
import com.aurora.imagehub.service.AiModelConfigService;
import com.aurora.starter.webmvc.domain.response.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** AI 创作入口；用户身份仅从登录态读取，提交接口只建立持久化任务。 */
@RestController
@RequestMapping("/api/app/generations")
@RequiredArgsConstructor
public class AiGenerationController {

    private final AiGenerationService aiGenerationService;

    private final AiModelConfigService aiModelConfigService;

    /** 返回可选模型的公开选项。 */
    @GetMapping("/models")
    public Result<List<AiModelVO>> models() {
        return Result.data(aiModelConfigService.available());
    }

    /** 相同 requestId 返回原任务，防止网络重试重复提交。 */
    @PostMapping
    public Result<GenerationVO> submit(@Valid @RequestBody GenerationParam param) {
        return Result.data(aiGenerationService.submit(StpUtil.getLoginIdAsLong(), param));
    }

    /** 返回本人历史，不包含模型密钥和临时图片字节。 */
    @GetMapping
    public Result<Page<GenerationVO>> history(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "12") int pageSize) {
        return Result.data(aiGenerationService.history(StpUtil.getLoginIdAsLong(), page, pageSize));
    }

    /** 页面重新打开时恢复尚未结束的任务。 */
    @GetMapping("/active")
    public Result<GenerationVO> active() {
        return Result.data(aiGenerationService.active(StpUtil.getLoginIdAsLong()));
    }

    /** 查询本人单个任务的最新状态。 */
    @GetMapping("/{id}")
    public Result<GenerationVO> task(@PathVariable String id) {
        return Result.data(aiGenerationService.task(StpUtil.getLoginIdAsLong(), id));
    }

    /** 仅重试保存已有结果，不重复调用生图模型。 */
    @PostMapping("/{id}/retry-save")
    public Result<GenerationVO> retrySave(@PathVariable String id) {
        return Result.data(aiGenerationService.retrySave(StpUtil.getLoginIdAsLong(), id));
    }

    /** 放弃待保存结果并释放预留额度。 */
    @PostMapping("/{id}/abandon")
    public Result<GenerationVO> abandon(@PathVariable String id) {
        long userId = StpUtil.getLoginIdAsLong();
        aiGenerationService.abandon(userId, id);
        return Result.data(aiGenerationService.task(userId, id));
    }
}
