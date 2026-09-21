package com.aurora.imagehub.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.aurora.imagehub.model.vo.QuotaUsageVO;
import com.aurora.imagehub.service.QuotaUsageService;
import com.aurora.starter.webmvc.domain.response.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 个人额度流水只读入口；用户身份来自登录态，没有前端记账或删除流水接口。 */
@RestController
@RequestMapping("/api/app/quota/records")
@RequiredArgsConstructor
public class QuotaUsageController {

    private final QuotaUsageService quotaUsageService;

    /** 只分页返回当前账号的实际消耗。 */
    @GetMapping
    public Result<Page<QuotaUsageVO>> history(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.data(quotaUsageService.history(StpUtil.getLoginIdAsLong(), page, pageSize));
    }
}
