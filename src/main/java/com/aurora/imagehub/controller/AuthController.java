package com.aurora.imagehub.controller;

import com.aurora.imagehub.service.UserAccountService;
import com.aurora.imagehub.service.UserInvitationService;
import com.aurora.imagehub.model.vo.InvitationInfoVO;
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

    private final UserInvitationService userInvitationService;

    private final AttemptLimiter attemptLimiter;

    /**
     * 游客预览公开活动规则；邀请码错误在注册前显示。
     */
    @GetMapping("/invitation")
    public Result<InvitationInfoVO> invitation(@RequestParam(defaultValue = "") String code, HttpServletRequest request) {
        attemptLimiter.check("invitation-preview-ip", request.getRemoteAddr(), 60, 300);
        return Result.data(userInvitationService.preview(code));
    }

    @PostMapping("/email-code")
    public Result<Void> sendCode(@Valid @RequestBody SendEmailCodeParam input, HttpServletRequest request) {
        attemptLimiter.check("email-ip", request.getRemoteAddr(), 5, 300);
        userAccountService.sendCode(input.getEmail());
        return Result.success("验证码已发送");
    }

    /** 注册来源由容器解析可信代理后取得，不接受请求体中的 IP 或奖励金额。 */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterParam input, HttpServletRequest request) {
        attemptLimiter.check("register-ip", request.getRemoteAddr(), 20, 300);
        userAccountService.register(input.getUsername(), input.getEmail(), input.getPassword(), input.getCode(), input.getInviteCode(), request.getRemoteAddr());
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

    /**
     * 上传专用头像，用户身份取自可信登录态，内容校验交给账户服务。
     */
    @PostMapping(value = "/avatar", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<UserVO> uploadAvatar(@RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return Result.data(userAccountService.uploadAvatar(cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong(), file));
    }

    /**
     * 仅允许选用本人图片，接口不接受任意头像 URL。
     */
    @PutMapping("/avatar")
    public Result<UserVO> selectAvatar(@Valid @RequestBody com.aurora.imagehub.model.param.AvatarParam param) {
        return Result.data(userAccountService.selectAvatar(cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong(), param.getImageId()));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        userAccountService.logout();
        return Result.success();
    }
}
