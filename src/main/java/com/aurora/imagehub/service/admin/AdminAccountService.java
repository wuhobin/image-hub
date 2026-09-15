package com.aurora.imagehub.service.admin;

import com.aurora.imagehub.model.entity.admin.AdminAccount;
import com.aurora.imagehub.model.vo.admin.*;
import com.baomidou.mybatisplus.extension.service.IService;

/** 管理员登录使用独立 admin 会话；受保护的管理业务通过 currentAdmin 检查账号仍存在，退出仅注销当前 Token。 */
public interface AdminAccountService extends IService<AdminAccount> {
    AdminLoginVO login(String username, String password);
    AdminVO currentAdmin();
    void logout();
}
