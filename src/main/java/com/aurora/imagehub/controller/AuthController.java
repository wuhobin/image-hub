package com.aurora.imagehub.controller;

import com.aurora.imagehub.service.UserAccountService;
import com.aurora.imagehub.ratelimit.AttemptLimiter;
import com.aurora.starter.webmvc.domain.response.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import com.aurora.imagehub.model.param.SendEmailCodeParam;
import com.aurora.imagehub.model.param.RegisterParam;
import com.aurora.imagehub.model.param.LoginParam;
import com.aurora.imagehub.model.vo.LoginVO;
import com.aurora.imagehub.model.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 认证 HTTP 入口，负责参数校验和 IP 限流，账户业务交给 Service 接口。 */
@RestController
@RequestMapping("/api/app/auth")
@RequiredArgsConstructor
@Tag(name = "用户认证")
public class AuthController {
    private final UserAccountService userAccountService;
    private final AttemptLimiter attemptLimiter;

    @PostMapping("/email-code")
    public Result<Void> sendCode(@Valid @RequestBody SendEmailCodeParam input, HttpServletRequest request) {
        attemptLimiter.check("email-ip", request.getRemoteAddr(), 5, 300);
        userAccountService.sendCode(input.getEmail());
        return Result.success("验证码已发送");
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterParam input, HttpServletRequest request) {
        attemptLimiter.check("register-ip", request.getRemoteAddr(), 20, 300);
        userAccountService.register(input.getUsername(), input.getEmail(), input.getPassword(), input.getCode());
        return Result.success("注册成功，请登录");
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginParam input, HttpServletRequest request) {
        attemptLimiter.check("login-ip", request.getRemoteAddr(), 40, 300);
        return Result.data(userAccountService.login(input.getUsername(), input.getPassword()));
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.data(userAccountService.currentUser());
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        userAccountService.logout();
        return Result.success();
    }
}
