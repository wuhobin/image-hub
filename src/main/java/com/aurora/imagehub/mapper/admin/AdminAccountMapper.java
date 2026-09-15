package com.aurora.imagehub.mapper.admin;

import com.aurora.imagehub.model.entity.admin.AdminAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/** 管理账号持久化，普通用户表不参与管理登录。 */
@Mapper
public interface AdminAccountMapper extends BaseMapper<AdminAccount> {
    @Select("SELECT * FROM hub_admin WHERE username = #{username} AND deleted = 0")
    AdminAccount findByUsername(String username);
}
