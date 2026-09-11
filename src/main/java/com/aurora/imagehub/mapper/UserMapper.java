package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.UserAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/** 用户账户持久化；唯一性由数据库约束兜底。 */
@Mapper
public interface UserMapper extends BaseMapper<UserAccount> {
    @Select("SELECT * FROM hub_user WHERE username = #{username}")
    UserAccount findByUsername(String username);

    @Select("SELECT * FROM hub_user WHERE id = #{id}")
    UserAccount findById(long id);

    @Select("SELECT COUNT(*) FROM hub_user WHERE email = #{email}")
    long countByEmail(String email);

}
