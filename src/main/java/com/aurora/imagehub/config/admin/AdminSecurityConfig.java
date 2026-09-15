package com.aurora.imagehub.config.admin;

import com.aurora.starter.security.account.AccountType;
import com.aurora.starter.security.account.AccountTypeDefinition;
import com.aurora.starter.security.account.SimpleAccountTypeDefinition;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 声明两类账号的接口范围；登录鉴权、白名单及未匹配路由兜底统一由平台负责。 */
@Configuration
public class AdminSecurityConfig {

    @Bean
    AccountTypeDefinition userAccountType() {
        // 保留 login 类型，与既有 StpUtil 会话兼容。
        return new SimpleAccountTypeDefinition(AccountType.LOGIN, List.of("/api/app/**"));
    }
    @Bean
    AccountTypeDefinition adminAccountType() {
        return new SimpleAccountTypeDefinition(AccountType.ADMIN, List.of("/api/admin/**"));
    }

}
