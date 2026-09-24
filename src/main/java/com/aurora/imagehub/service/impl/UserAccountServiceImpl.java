package com.aurora.imagehub.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.crypto.digest.BCrypt;
import com.aurora.imagehub.mapper.UserMapper;
import com.aurora.imagehub.mapper.ImageMapper;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.service.ImageFileService;
import com.aurora.imagehub.service.UserAccountService;
import com.aurora.imagehub.service.UserInvitationService;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 用户账户服务实现：负责验证码注册、密码校验与独立 Token 会话。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAccountServiceImpl extends ServiceImpl<UserMapper, UserAccount> implements UserAccountService {
    private static final VerificationScene REGISTER = () -> "IMAGE_HUB_REGISTER";

    // 不存在的账户也执行 BCrypt 校验，减少用户名是否存在带来的耗时差异。
    private static final String DUMMY_HASH = BCrypt.hashpw("not-a-user-password", BCrypt.gensalt(12));

    private final UserMapper userMapper;

    private final ObjectProvider<MailVerificationService> mailVerificationServiceProvider;

    private final AttemptLimiter attemptLimiter;

    private final UploadQuotaCache uploadQuotaCache;

    private final UserInvitationService userInvitationService;

    private final ImageMapper imageMapper;

    private final ImageFileService imageFileService;

    private final RedissonClient redissonClient;

    private final PlatformTransactionManager platformTransactionManager;

    @Override
    public void sendCode(String email) {
        email = normalizeEmail(email);
        if (userMapper.countByEmail(email) > 0) throw new BizException(409, "该邮箱已注册");
        mailService().send(new MailVerificationSendRequest(email, REGISTER, "Image Hub 注册验证码",
                "你的注册验证码是 {code}，{expireMinutes} 分钟内有效。如非本人操作，请忽略此邮件。",
                MailContentType.TEXT));
    }

    /**
     * 邀请码错误不消费验证码；持久化任一步失败均回滚账号及奖励，缓存初始化在提交后尽力执行。
     */
    @Override
    public void register(String username, String email, String password, String code, String inviteCode, String clientIp) {
        username = username.trim();
        email = normalizeEmail(email);
        validatePassword(password);
        if (userMapper.countByUsername(username) > 0 || userMapper.countByEmail(email) > 0) {
            throw new BizException(409, "用户名或邮箱已被注册");
        }
        userInvitationService.validateCode(inviteCode);
        attemptLimiter.check("register", email, 10, 300);
        if (!mailService().verifyAndConsume(new MailVerificationVerifyRequest(email, REGISTER, code))) {
            throw new BizException(400, "验证码错误或已过期，请重新获取");
        }
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt(12)));
        new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException e) {
                throw new BizException(409, "用户名或邮箱已被注册");
            }
            userInvitationService.recordRegistration(user.getId(), inviteCode, clientIp);
        });
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

    /**
     * 新头像上传不走图库计费，原头像只在新头像保存成功后清理。
     */
    @Override
    public UserVO uploadAvatar(long userId, MultipartFile file) {
        attemptLimiter.check("avatar-upload", Long.toString(userId), 10, 300);
        return changeAvatar(userId, file, null);
    }

    /**
     * 选用图库图片只更新引用，保留图片本身及其消费记录。
     */
    @Override
    public UserVO selectAvatar(long userId, String imageId) {
        return changeAvatar(userId, null, imageId);
    }

    /**
     * 同用户串行更换，云上传在事务外，头像引用和新图片记录在同一短事务中提交。
     * 旧云文件清理失败保留图片记录，下次更换前重试，避免免费上传持续堆积文件。
     */
    private UserVO changeAvatar(long userId, MultipartFile file, String imageId) {
        var lock = redissonClient.getLock("image-hub:avatar:" + userId);
        if (!lock.tryLock()) throw new BizException(409, "头像正在更新，请稍后重试");
        try {
            UserAccount user = userMapper.findById(userId);
            if (user == null) throw new BizException(401, "登录已失效，请重新登录");
            ImageFile selected = file == null ? imageMapper.findOwned(userId, imageId) : null;
            if (file == null && (selected == null || !java.util.Set.of("UPLOAD", "AI").contains(selected.getSourceType()))) {
                throw new BizException(404, "图片不存在或已删除，请重新选择");
            }
            cleanupUnusedAvatars(userId);
            ImageFile uploaded = file == null ? null : imageFileService.storeAvatar(userId, file);
            String nextId = uploaded == null ? imageId : uploaded.getId();
            try {
                new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
                    if (!lock.isHeldByCurrentThread()) throw new BizException(503, "头像更新权限已失效，请重试");
                    if (uploaded != null) imageMapper.insert(uploaded);
                    if (userMapper.updateAvatar(userId, nextId) != 1)
                        throw new BizException(409, "图片已变化，请重新选择");
                });
            } catch (RuntimeException error) {
                if (uploaded != null) {
                    // 写库结果不明时先回读，不能误删已被成功保存的新头像。
                    try {
                        if (imageMapper.findOwned(userId, nextId) == null)
                            imageFileService.discardUncommitted(uploaded);
                    } catch (RuntimeException verification) {
                        log.warn("头像入库状态无法确认，保留云文件：userId={}, imageId={}", userId, nextId);
                    }
                }
                throw error;
            }
            try {
                if (lock.isHeldByCurrentThread()) cleanupUnusedAvatars(userId);
            } catch (RuntimeException cleanup) {
                // 更新已提交，不能误报保存失败；保留旧文件记录供下一次请求清理。
                log.warn("旧头像清理失败，保留记录待下次更换重试：userId={}", userId);
            }
            return response(userMapper.findById(userId));
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            } catch (RuntimeException unlock) {
                log.warn("头像锁释放失败，等待租期过期：userId={}", userId);
            }
        }
    }

    /**
     * 只清理未被当前头像引用的专用文件，普通图库图片和 AI 作品永远不由头像更新删除。
     */
    private void cleanupUnusedAvatars(long userId) {
        for (ImageFile image : imageMapper.unusedAvatars(userId)) imageFileService.delete(userId, image.getId());
    }

    private UserVO response(UserAccount user) {
        if (user == null) throw new BizException(401, "登录已失效，请重新登录");
        ImageFile avatar = user.getAvatarImageId() == null ? null : imageMapper.findOwned(user.getId(), user.getAvatarImageId());
        String avatarUrl = com.aurora.imagehub.model.vo.ImageVO.avatarUrl(avatar);
        return new UserVO(user.getId().toString(), user.getUsername(), user.getEmail(), avatarUrl);
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
