package com.aurora.imagehub.controller.admin;

import com.aurora.imagehub.model.param.AiModelConfigParam;
import com.aurora.imagehub.model.vo.admin.AiModelConfigVO;
import com.aurora.imagehub.service.AiModelConfigService;
import com.aurora.starter.webmvc.domain.response.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 生图模型配置入口；业务层再次校验管理员身份，响应不回显密钥。 */
@RestController
@RequestMapping("/api/admin/ai-models")
@RequiredArgsConstructor
public class AdminAiModelController {

    private final AiModelConfigService aiModelConfigService;

    /** 分页查看模型配置和密钥是否已配置。 */
    @GetMapping
    public Result<Page<AiModelConfigVO>> models(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        return Result.data(aiModelConfigService.models(page, pageSize));
    }

    /** 新增 OpenAI Images 兼容模型。 */
    @PostMapping
    public Result<AiModelConfigVO> create(@Valid @RequestBody AiModelConfigParam param) {
        return Result.data(aiModelConfigService.saveModel(null, param));
    }

    /** 修改配置；API Key 留空保留原有密钥。 */
    @PutMapping("/{id}")
    public Result<AiModelConfigVO> update(@PathVariable long id, @Valid @RequestBody AiModelConfigParam param) {
        return Result.data(aiModelConfigService.saveModel(id, param));
    }

    /** 逻辑删除模型，新任务不可再选用。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        aiModelConfigService.archiveModel(id);
        return Result.success();
    }
}
