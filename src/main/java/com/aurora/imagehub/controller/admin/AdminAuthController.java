package com.aurora.imagehub.controller.admin;

import com.aurora.imagehub.model.param.LoginParam;
import com.aurora.imagehub.model.vo.admin.*;
import com.aurora.imagehub.ratelimit.AttemptLimiter;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.starter.webmvc.domain.response.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 管理登录 HTTP 入口；不提供注册及账户写操作。 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {
    private final AdminAccountService adminAccountService;
    private final AttemptLimiter attemptLimiter;

    @PostMapping("/login")
    public Result<AdminLoginVO> login(@Valid @RequestBody LoginParam input, HttpServletRequest request) {
        attemptLimiter.check("admin-login-ip", request.getRemoteAddr(), 30, 300);
        return Result.data(adminAccountService.login(input.getUsername(), input.getPassword()));
    }
    @GetMapping("/me")
    public Result<AdminVO> me() { return Result.data(adminAccountService.currentAdmin()); }
    @PostMapping("/logout")
    public Result<Void> logout() {
        adminAccountService.logout();
        return Result.success();
    }
}
