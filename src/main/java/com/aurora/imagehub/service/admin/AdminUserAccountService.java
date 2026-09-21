package com.aurora.imagehub.service.admin;

import com.aurora.imagehub.model.entity.UserAccount;
import com.aurora.imagehub.model.param.admin.AdminUserQueryParam;
import com.aurora.imagehub.model.vo.admin.AdminUserVO;
import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/** 管理员只读用户查询；仅直接读取 Redis 剩余积分，不恢复、不修改积分。 */
public interface AdminUserAccountService extends IService<UserAccount> {
    Page<AdminUserVO> listUsers(AdminUserQueryParam query);
}
