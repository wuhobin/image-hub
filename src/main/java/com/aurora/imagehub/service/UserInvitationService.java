package com.aurora.imagehub.service;

import com.aurora.imagehub.model.entity.UserInvitation;
import com.aurora.imagehub.model.vo.*;
import com.aurora.imagehub.model.vo.admin.AdminInvitationVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 直接邀请业务；只允许注册事务创建关系，奖励快照与双方收入同事务，普通用户不得审核。
 */
public interface UserInvitationService extends IService<UserInvitation> {

    /**
     * 注册前校验邀请码，空值代表普通注册；错误不会消费邮箱验证码。
     */
    void validateCode(String inviteCode);

    /**
     * 游客查看当前活动规则及邀请人展示名，不公开邮箱或账户编号。
     */
    InvitationInfoVO preview(String inviteCode);

    /**
     * 从可信登录态取得用户编号，为老用户按需生成稳定邀请码。
     */
    InvitationInfoVO overview(long userId);

    /**
     * 仅供注册事务在账户插入后调用；无邀请码不产生关系或奖励。
     */
    void recordRegistration(long userId, String inviteCode, String clientIp);

    /**
     * 读取本人邀请和受邀记录，不允许指定他人的查询范围。
     */
    Page<InvitationVO> history(long userId, int page, int pageSize);

    /**
     * 管理员查看全部关系及可疑原因。
     */
    Page<AdminInvitationVO> adminHistory(String status, int page, int pageSize);

    /**
     * 审核待发奖励；相同结论可重试，相反结论不可覆盖，关闭活动不影响历史审批。
     */
    void review(long id, boolean approved, String note);
}
