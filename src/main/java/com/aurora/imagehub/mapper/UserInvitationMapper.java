package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.UserInvitation;
import com.aurora.imagehub.model.vo.InvitationVO;
import com.aurora.imagehub.model.vo.admin.AdminInvitationVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

/**
 * 邀请记录持久化；用户读写限定归属，管理员查询由业务层验证身份。
 */
@Mapper
public interface UserInvitationMapper extends BaseMapper<UserInvitation> {

    /**
     * 调用方已锁定邀请人；统计数据库时间前十分钟的成功注册，不统计失败请求。
     */
    @Select("""
            SELECT COUNT(*) FROM hub_user_invitation
            WHERE inviter_id = #{inviterId} AND register_ip = #{ip} AND deleted = 0
              AND create_time > TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP)
            """)
    long recentCount(@Param("inviterId") long inviterId, @Param("ip") String ip);

    /**
     * 原生分页由平台拦截器处理；普通用户只能读取自己参与的关系及自己的奖励。
     */
    @Select("""
            SELECT i.id, CASE WHEN i.inviter_id = #{userId} THEN b.username ELSE a.username END AS counterparty_name,
              CASE WHEN i.inviter_id = #{userId} THEN TRUE ELSE FALSE END AS inviter,
              CASE WHEN i.inviter_id = #{userId} THEN i.inviter_points ELSE i.invitee_points END AS points,
              i.status, i.create_time
            FROM hub_user_invitation i
            LEFT JOIN hub_user a ON a.id = i.inviter_id AND a.deleted = 0
            LEFT JOIN hub_user b ON b.id = i.invitee_id AND b.deleted = 0
            WHERE (i.inviter_id = #{userId} OR i.invitee_id = #{userId}) AND i.deleted = 0
            ORDER BY i.create_time DESC, i.id DESC
            """)
    Page<InvitationVO> history(Page<InvitationVO> page, @Param("userId") long userId);

    /**
     * 管理列表保留已注销账号的历史关系，支持按状态筛选。
     */
    @Select("""
            <script>
            SELECT i.*, a.username AS inviter_name, b.username AS invitee_name
            FROM hub_user_invitation i
            LEFT JOIN hub_user a ON a.id = i.inviter_id AND a.deleted = 0
            LEFT JOIN hub_user b ON b.id = i.invitee_id AND b.deleted = 0
            WHERE i.deleted = 0
            <if test="status != null and status != ''">AND i.status = #{status}</if>
            ORDER BY i.create_time DESC, i.id DESC
            </script>
            """)
    Page<AdminInvitationVO> adminHistory(Page<AdminInvitationVO> page, @Param("status") String status);

    /**
     * 行锁串行化审批；重复审核读取已提交结论，不再次发奖。
     */
    @Select("SELECT * FROM hub_user_invitation WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    UserInvitation lockForReview(long id);
}
