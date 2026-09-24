package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.UserAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/** 用户账户持久化；唯一性由数据库约束兜底。 */
@Mapper
public interface UserMapper extends BaseMapper<UserAccount> {

    /**
     * 在签到事务内串行化同账号请求，跨后端实例仍只能领取一次。
     */
    @Select("SELECT id FROM hub_user WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Long lockForCheckIn(long id);

    @Select("SELECT * FROM hub_user WHERE username = #{username} AND deleted = 0")
    UserAccount findByUsername(String username);

    @Select("SELECT * FROM hub_user WHERE id = #{id} AND deleted = 0")
    UserAccount findById(long id);

    /**
     * 头像更新同时校验用户与图片归属，逻辑删除图片不能再次选用。
     */
    @Update("""
            UPDATE hub_user SET avatar_image_id = #{imageId}, update_time = CURRENT_TIMESTAMP
            WHERE id = #{userId} AND deleted = 0
              AND EXISTS (SELECT 1 FROM hub_image WHERE id = #{imageId} AND user_id = #{userId} AND deleted = 0)
            """)
    int updateAvatar(@Param("userId") long userId, @Param("imageId") String imageId);

    /** 唯一约束包含已删除账户，注册前仍需检查占用。 */
    @Select("SELECT COUNT(*) FROM hub_user WHERE username = #{username}")
    long countByUsername(String username);

    /** 邮箱唯一约束包含已删除账户。 */
    @Select("SELECT COUNT(*) FROM hub_user WHERE email = #{email}")
    long countByEmail(String email);

    /**
     * 查询有效邀请人，无效邀请码不允许继续提交注册。
     */
    @Select("SELECT * FROM hub_user WHERE invite_code = #{code} AND deleted = 0")
    UserAccount findByInviteCode(String code);

    /**
     * 注册事务内锁定邀请人，串行化集中注册计数，不能在事务外调用。
     */
    @Select("SELECT * FROM hub_user WHERE invite_code = #{code} AND deleted = 0 FOR UPDATE")
    UserAccount lockByInviteCode(String code);

    /**
     * 只填充空邀请码，已有邀请码永不覆盖；唯一性包括已删除账号。
     */
    @Update("UPDATE hub_user SET invite_code = #{code}, update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND invite_code IS NULL AND deleted = 0")
    int initializeInviteCode(@Param("id") long id, @Param("code") String code);

}
