package com.aurora.imagehub.controller.admin;

import com.aurora.imagehub.model.param.admin.AdminSettingsParam;
import com.aurora.imagehub.model.vo.admin.AdminSettingsVO;
import com.aurora.imagehub.service.admin.SystemSettingsService;
import com.aurora.starter.webmvc.domain.response.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 管理员全局设置入口，仅提供既定配置的读取和修改。 */
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final SystemSettingsService systemSettingsService;

    /** 返回管理页面所需的配置，管理员身份与配置有效性由业务层校验。 */
    @GetMapping
    public Result<AdminSettingsVO> get() {
        return Result.data(systemSettingsService.settings());
    }

    /** 校验原始请求数值后保存预置配置，避免小数在整数转换时被静默截断。 */
    @PutMapping
    public Result<AdminSettingsVO> update(@Valid @RequestBody AdminSettingsParam param) {
        return Result.data(systemSettingsService.updateSettings(param.getFreeUploadQuota().intValueExact()));
    }
}
