package com.aurora.imagehub.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.crypto.digest.BCrypt;
import com.aurora.imagehub.mapper.UserMapper;
import com.aurora.imagehub.service.UserAccountService;
import com.aurora.imagehub.ratelimit.AttemptLimiter;
import com.aurora.imagehub.cache.UploadQuotaCache;
import com.aurora.imagehub.model.vo.LoginVO;
import com.aurora.imagehub.model.vo.UserVO;
import com.aurora.imagehub.model.entity.UserAccount;
import com.aurora.starter.verification.mail.*;
import com.aurora.starter.verification.scene.VerificationScene;
import com.aurora.starter.webmvc.exception.BizException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 用户账户服务实现：负责验证码注册、密码校验与独立 Token 会话。 */
@Service
@RequiredArgsConstructor
public class UserAccountServiceImpl extends ServiceImpl<UserMapper, UserAccount> implements UserAccountService {
    private static final VerificationScene REGISTER = () -> "IMAGE_HUB_REGISTER";
    // 不存在的账户也执行 BCrypt 校验，减少用户名是否存在带来的耗时差异。
    private static final String DUMMY_HASH = BCrypt.hashpw("not-a-user-password", BCrypt.gensalt(12));
    private final UserMapper userMapper;
    private final ObjectProvider<MailVerificationService> mailVerificationServiceProvider;
    private final AttemptLimiter attemptLimiter;
    private final UploadQuotaCache uploadQuotaCache;

    @Override
    public void sendCode(String email) {
        email = normalizeEmail(email);
        if (userMapper.countByEmail(email) > 0) throw new BizException(409, "该邮箱已注册");
        mailService().send(new MailVerificationSendRequest(email, REGISTER, "Image Hub 注册验证码",
                "你的注册验证码是 {code}，{expireMinutes} 分钟内有效。如非本人操作，请忽略此邮件。",
                MailContentType.TEXT));
    }

    @Override
    public void register(String username, String email, String password, String code) {
        username = username.trim();
        email = normalizeEmail(email);
        validatePassword(password);
        if (userMapper.countByUsername(username) > 0 || userMapper.countByEmail(email) > 0) {
            throw new BizException(409, "用户名或邮箱已被注册");
        }
        attemptLimiter.check("register", email, 10, 300);
        if (!mailService().verifyAndConsume(new MailVerificationVerifyRequest(email, REGISTER, code))) {
            throw new BizException(400, "验证码错误或已过期，请重新获取");
        }
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt(12)));
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "用户名或邮箱已被注册");
        }
        uploadQuotaCache.initialize(user.getId());
    }

    @Override
    public LoginVO login(String username, String password) {
        username = username.trim();
        validatePassword(password);
        attemptLimiter.check("login-account", username.toLowerCase(Locale.ROOT), 15, 300);
        UserAccount user = userMapper.findByUsername(username);
        boolean valid = BCrypt.checkpw(password, user == null ? DUMMY_HASH : user.getPasswordHash());
        if (user == null || !valid) throw new BizException(400, "用户名或密码错误");
        // 各设备独立持有 Token，退出当前设备不会使其他设备失效。
        StpUtil.login(user.getId(), new SaLoginParameter().setIsConcurrent(true)
                .setIsShare(false));
        return new LoginVO(StpUtil.getTokenValue(), StpUtil.getTokenTimeout(), response(user));
    }

    @Override
    public UserVO currentUser() {
        UserAccount user = userMapper.findById(StpUtil.getLoginIdAsLong());
        if (user == null) {
            StpUtil.logout();
            throw new BizException(401, "登录已失效，请重新登录");
        }
        return response(user);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    private UserVO response(UserAccount user) {
        return new UserVO(user.getId().toString(), user.getUsername(), user.getEmail());
    }

    private MailVerificationService mailService() {
        MailVerificationService service = mailVerificationServiceProvider.getIfAvailable();
        if (service == null) throw new BizException(503, "邮件服务尚未启用，请联系管理员");
        return service;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void validatePassword(String password) {
        if (password.length() < 6 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BizException(400, "密码至少 6 位，UTF-8 长度不能超过 72 字节");
        }
    }
}
