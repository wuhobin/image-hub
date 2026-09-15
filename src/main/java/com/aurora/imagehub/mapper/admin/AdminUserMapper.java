package com.aurora.imagehub.mapper.admin;

import com.aurora.imagehub.model.entity.UserAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

/** 管理员专用跨用户只读查询；调用方必须已通过管理鉴权。 */
@Mapper
public interface AdminUserMapper extends BaseMapper<UserAccount> {
    @Select("""
        <script>
        SELECT id, username, email, create_time FROM hub_user WHERE deleted = 0
        <if test="search != null and search != ''">
          AND (LOCATE(LOWER(#{search}), LOWER(username)) > 0
            OR LOCATE(LOWER(#{search}), LOWER(email)) > 0)
        </if>
        ORDER BY create_time DESC, id DESC
        </script>
        """)
    Page<UserAccount> listUsers(Page<UserAccount> page, @Param("search") String search);
}
