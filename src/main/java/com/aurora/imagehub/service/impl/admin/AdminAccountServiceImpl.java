package com.aurora.imagehub.service.impl.admin;

import cn.hutool.crypto.digest.BCrypt;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.aurora.imagehub.mapper.admin.AdminAccountMapper;
import com.aurora.imagehub.model.entity.admin.AdminAccount;
import com.aurora.imagehub.model.vo.admin.*;
import com.aurora.imagehub.ratelimit.AttemptLimiter;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.starter.security.account.AccountType;
import com.aurora.starter.security.context.SecurityUtils;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 独立管理员认证，不允许普通账号凭据或 Token 提升为管理身份。 */
@Service
@RequiredArgsConstructor
public class AdminAccountServiceImpl extends ServiceImpl<AdminAccountMapper, AdminAccount> implements AdminAccountService {
    private static final String DUMMY_HASH = BCrypt.hashpw("not-an-admin-password", BCrypt.gensalt(12));
    private final AdminAccountMapper adminAccountMapper;
    private final AttemptLimiter attemptLimiter;

    @Override
    public AdminLoginVO login(String username, String password) {
        username = username.trim();
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw new BizException(400, "密码长度无效");
        attemptLimiter.check("admin-login-account", username.toLowerCase(Locale.ROOT), 10, 300);
        AdminAccount account = adminAccountMapper.findByUsername(username);
        // 不存在的账号也校验哈希，减少可用于探测账号的时间差异。
        boolean valid = BCrypt.checkpw(password, account == null ? DUMMY_HASH : account.getPasswordHash());
        if (account == null || !valid) throw new BizException(400, "管理员账号或密码错误");
        SecurityUtils.loginAs(AccountType.ADMIN, account.getId(),
                new SaLoginParameter().setIsConcurrent(true).setIsShare(false));
        return new AdminLoginVO(SecurityUtils.getTokenValueAs(AccountType.ADMIN),
                SecurityUtils.getTokenInfoAs(AccountType.ADMIN).getTokenTimeout(), response(account));
    }

    @Override
    public AdminVO currentAdmin() {
        // 获取可信 ID 本身会拒绝未登录调用；业务层只补充账号有效性校验。
        AdminAccount account = adminAccountMapper.selectById(SecurityUtils.getLoginIdAsLongAs(AccountType.ADMIN));
        if (account == null) {
            SecurityUtils.logoutAs(AccountType.ADMIN);
            throw new BizException(401, "管理员登录已失效，请重新登录");
        }
        return response(account);
    }

    @Override
    public void logout() {
        currentAdmin();
        SecurityUtils.logoutAs(AccountType.ADMIN);
    }

    private AdminVO response(AdminAccount account) {
        return new AdminVO(account.getId().toString(), account.getUsername());
    }
}
