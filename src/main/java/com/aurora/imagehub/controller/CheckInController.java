package com.aurora.imagehub.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.aurora.imagehub.model.vo.CheckInVO;
import com.aurora.imagehub.service.UserCheckInService;
import com.aurora.starter.webmvc.domain.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 签到只操作当前登录用户，不接受日期、用户或奖励金额等客户端参数。
 */
@RestController
@RequestMapping("/api/app/check-in")
@RequiredArgsConstructor
public class CheckInController {

    private final UserCheckInService userCheckInService;

    /**
     * 查询当天资格及连续签到进度。
     */
    @GetMapping
    public Result<CheckInVO> status() {
        return Result.data(userCheckInService.status(StpUtil.getLoginIdAsLong()));
    }

    /**
     * 主动领取当天奖励，重复请求返回当天结果，不重复记账。
     */
    @PostMapping
    public Result<CheckInVO> checkIn() {
        return Result.data(userCheckInService.checkIn(StpUtil.getLoginIdAsLong()));
    }
}
