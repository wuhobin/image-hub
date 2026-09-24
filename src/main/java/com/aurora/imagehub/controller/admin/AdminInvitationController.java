package com.aurora.imagehub.controller.admin;

import com.aurora.imagehub.model.param.admin.InvitationReviewParam;
import com.aurora.imagehub.model.vo.admin.AdminInvitationVO;
import com.aurora.imagehub.service.UserInvitationService;
import com.aurora.starter.webmvc.domain.response.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 邀请审核入口；业务层再次验证管理员身份，审核不能改变注册时的奖励金额。
 */
@RestController
@RequestMapping("/api/admin/invitations")
@RequiredArgsConstructor
public class AdminInvitationController {

    private final UserInvitationService userInvitationService;

    /**
     * 查看邀请记录，默认仅展示需要人工处理的记录。
     */
    @GetMapping
    public Result<Page<AdminInvitationVO>> list(@RequestParam(defaultValue = "PENDING") String status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        return Result.data(userInvitationService.adminHistory(status, page, pageSize));
    }

    /**
     * 同时审核双方奖励；重复提交同一结论不重复发奖。
     */
    @PostMapping("/{id}/review")
    public Result<Void> review(@PathVariable long id, @Valid @RequestBody InvitationReviewParam param) {
        userInvitationService.review(id, param.getApproved(), param.getNote());
        return Result.success(param.getApproved() ? "审核通过，双方奖励已发放" : "已拒绝此次邀请奖励");
    }
}
