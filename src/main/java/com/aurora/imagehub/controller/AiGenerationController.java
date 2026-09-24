package com.aurora.imagehub.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.aurora.imagehub.model.param.GenerationParam;
import com.aurora.imagehub.model.param.ShareCreationParam;
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
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

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
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<GenerationVO> submit(@Valid @RequestBody GenerationParam param) {
        return Result.data(aiGenerationService.submit(StpUtil.getLoginIdAsLong(), param));
    }

    /** 单张参考图与参数一并提交；身份取自登录态，不接受客户端指定参考图 URL。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<GenerationVO> submitWithReference(@Valid @RequestPart("param") GenerationParam param,
                                                   @RequestPart("reference") List<MultipartFile> references) {
        if (references.size() != 1) throw new com.aurora.starter.webmvc.exception.BizException(400, "一次只能上传一张参考图");
        return Result.data(aiGenerationService.submit(StpUtil.getLoginIdAsLong(), param, references.getFirst()));
    }

    /**
     * 返回本人历史，先筛选再分页；不传筛选参数时保持旧接口行为，不包含模型密钥。
     */
    @GetMapping
    public Result<Page<GenerationVO>> history(@Valid @ModelAttribute com.aurora.imagehub.model.param.GenerationHistoryParam param) {
        return Result.data(aiGenerationService.history(StpUtil.getLoginIdAsLong(), param));
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

    /**
     * 删除本人已结束的创作及图片，文件删除失败时保留记录供重试。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        aiGenerationService.delete(StpUtil.getLoginIdAsLong(), id);
        return Result.success();
    }

    /**
     * 发布本人成功作品或修改提示词可见性，用户身份仅取自登录态。
     */
    @PutMapping("/{id}/share")
    public Result<GenerationVO> share(@PathVariable String id, @Valid @RequestBody ShareCreationParam param) {
        return Result.data(aiGenerationService.share(StpUtil.getLoginIdAsLong(), id, param.getPromptPublic()));
    }

    /**
     * 撤销本人分享；保留原作品，不删除存储文件。
     */
    @DeleteMapping("/{id}/share")
    public Result<Void> revokeShare(@PathVariable String id) {
        aiGenerationService.revokeShare(StpUtil.getLoginIdAsLong(), id);
        return Result.success();
    }

    /** 兼容旧客户端路径，校验归属后返回不再支持暂存重试的提示。 */
    @PostMapping("/{id}/retry-save")
    public Result<GenerationVO> retrySave(@PathVariable String id) {
        return Result.data(aiGenerationService.retrySave(StpUtil.getLoginIdAsLong(), id));
    }

    /** 兼容旧客户端路径，当前已无可放弃的暂存结果。 */
    @PostMapping("/{id}/abandon")
    public Result<GenerationVO> abandon(@PathVariable String id) {
        long userId = StpUtil.getLoginIdAsLong();
        aiGenerationService.abandon(userId, id);
        return Result.data(aiGenerationService.task(userId, id));
    }
}
