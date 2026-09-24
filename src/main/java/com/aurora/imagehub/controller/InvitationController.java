package com.aurora.imagehub.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.aurora.imagehub.model.vo.InvitationInfoVO;
import com.aurora.imagehub.model.vo.InvitationVO;
import com.aurora.imagehub.service.UserInvitationService;
import com.aurora.starter.webmvc.domain.response.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 个人邀请入口；归属只能来自登录态，不提供补填、改绑或发奖接口。
 */
@RestController
@RequestMapping("/api/app/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final UserInvitationService userInvitationService;

    /**
     * 返回当前用户的邀请码及活动规则。
     */
    @GetMapping
    public Result<InvitationInfoVO> overview() {
        return Result.data(userInvitationService.overview(StpUtil.getLoginIdAsLong()));
    }

    /**
     * 返回本人参与的邀请和奖励状态。
     */
    @GetMapping("/records")
    public Result<Page<InvitationVO>> history(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int pageSize) {
        return Result.data(userInvitationService.history(StpUtil.getLoginIdAsLong(), page, pageSize));
    }
}
