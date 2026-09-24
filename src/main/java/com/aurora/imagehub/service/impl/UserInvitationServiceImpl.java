package com.aurora.imagehub.service.impl;

import com.aurora.imagehub.mapper.UserInvitationMapper;
import com.aurora.imagehub.mapper.UserMapper;
import com.aurora.imagehub.model.entity.UserAccount;
import com.aurora.imagehub.model.entity.UserInvitation;
import com.aurora.imagehub.model.vo.*;
import com.aurora.imagehub.model.vo.admin.AdminInvitationVO;
import com.aurora.imagehub.service.QuotaUsageService;
import com.aurora.imagehub.service.UserInvitationService;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.imagehub.service.admin.SystemSettingsService;
import com.aurora.starter.mybatisplus.mybatis.PageUtils;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册邀请、集中注册识别及人工审核；数据库是邀请状态和积分收入的唯一依据。
 */
@Service
@RequiredArgsConstructor
public class UserInvitationServiceImpl extends ServiceImpl<UserInvitationMapper, UserInvitation> implements UserInvitationService {

    private final UserMapper userMapper;

    private final QuotaUsageService quotaUsageService;

    private final SystemSettingsService systemSettingsService;

    private final AdminAccountService adminAccountService;

    /**
     * 无效邀请码必须先纠正或清空，不能静默转成无奖励注册。
     */
    @Override
    public void validateCode(String inviteCode) {
        String code = normalize(inviteCode);
        if (!code.isEmpty()) requireInviter(code);
    }

    /**
     * 预览金额仅用于展示，实际金额在注册事务内重新读取并固定。
     */
    @Override
    public InvitationInfoVO preview(String inviteCode) {
        String code = normalize(inviteCode);
        return info(code.isEmpty() ? null : requireInviter(code), systemSettingsService.invitationRewards());
    }

    /**
     * 条件更新保证并发打开页面不会重置已生成的邀请码，不需要为老用户批量回填。
     */
    @Override
    public InvitationInfoVO overview(long userId) {
        UserAccount user = userMapper.findById(userId);
        if (user == null) throw new BizException(401, "账号不可用，请重新登录");
        if (user.getInviteCode() == null) {
            userMapper.initializeInviteCode(userId, UUID.randomUUID().toString().replace("-", ""));
            user = userMapper.findById(userId);
            if (user == null) throw new BizException(401, "账号不可用，请重新登录");
        }
        return info(user, systemSettingsService.invitationRewards());
    }

    /**
     * 同邀请人行锁覆盖计数与插入，多个实例同时注册也只能有前两次自动发奖。
     * ponytail: 同一邀请人的注册短事务串行化；若成为热点，再引入按 IP 分组的专用锁记录。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRegistration(long userId, String inviteCode, String clientIp) {
        String code = normalize(inviteCode);
        if (code.isEmpty()) return;
        UserAccount inviter = userMapper.lockByInviteCode(code);
        if (inviter == null) throw invalidCode();
        if (inviter.getId() == userId) throw new BizException(40021, "不能邀请自己");
        if (clientIp == null || clientIp.isBlank() || clientIp.length() > 45) {
            throw new BizException(503, "无法确认注册来源，请稍后重试");
        }
        InvitationSettingsVO rewards = systemSettingsService.invitationRewards();
        UserInvitation entry = new UserInvitation();
        entry.setInviterId(inviter.getId());
        entry.setInviteeId(userId);
        entry.setRegisterIp(clientIp);
        entry.setInviterPoints(rewards.getInviterPoints());
        entry.setInviteePoints(rewards.getInviteePoints());
        boolean suspicious = baseMapper.recentCount(inviter.getId(), clientIp) >= 2;
        entry.setStatus(!rewards.getEnabled() ? "DISABLED" : suspicious ? "PENDING" : "PAID");
        if (suspicious) entry.setRiskReason("同一 IP、同一邀请人，10 分钟内第 3 个及之后的成功注册");
        if (!save(entry)) throw new IllegalStateException("邀请关系保存失败");
        if ("PAID".equals(entry.getStatus())) pay(entry);
    }

    /**
     * 用户范围固定来自登录态；页面大小受限以避免无界查询。
     */
    @Override
    public Page<InvitationVO> history(long userId, int page, int pageSize) {
        validatePage(page, pageSize);
        return baseMapper.history(PageUtils.buildPage(page, pageSize), userId);
    }

    /**
     * 管理员身份在服务端验证，客户端传入的筛选值仅作为查询条件。
     */
    @Override
    public Page<AdminInvitationVO> adminHistory(String status, int page, int pageSize) {
        adminAccountService.currentAdmin();
        validatePage(page, pageSize);
        if (status != null && !status.isEmpty() && !Set.of("PAID", "PENDING", "REJECTED", "DISABLED").contains(status)) {
            throw new BizException(400, "邀请状态无效");
        }
        return baseMapper.adminHistory(PageUtils.buildPage(page, pageSize), status);
    }

    /**
     * 审核状态、审核人和双方积分共用事务；任意一方记账失败则全部回滚，允许原请求重试。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void review(long id, boolean approved, String note) {
        long adminId = Long.parseLong(adminAccountService.currentAdmin().getId());
        if (note != null && note.length() > 255) throw new BizException(400, "审核备注不能超过 255 字");
        UserInvitation entry = baseMapper.lockForReview(id);
        if (entry == null) throw new BizException(404, "邀请记录不存在");
        String status = approved ? "PAID" : "REJECTED";
        if (!"PENDING".equals(entry.getStatus())) {
            if (status.equals(entry.getStatus()) && entry.getReviewerId() != null) return;
            throw new BizException(409, "该邀请已处理，请刷新列表");
        }
        if (approved && (userMapper.findById(entry.getInviterId()) == null || userMapper.findById(entry.getInviteeId()) == null)) {
            throw new BizException(409, "邀请双方存在不可用账号，不能发放奖励");
        }
        entry.setStatus(status);
        entry.setReviewerId(adminId);
        entry.setReviewNote(note == null ? "" : note.trim());
        if (!updateById(entry)) throw new IllegalStateException("审核保存失败");
        if (approved) pay(entry);
    }

    /**
     * 不修改 Redis 消耗值，现有积分查询自动累加持久化收入；业务唯一键另行防重。
     */
    private void pay(UserInvitation entry) {
        String bizId = entry.getId().toString();
        quotaUsageService.recordIncome(entry.getInviterId(), "INVITATION_REWARD", bizId, entry.getInviterPoints(), "邀请新用户注册");
        quotaUsageService.recordIncome(entry.getInviteeId(), "INVITEE_REWARD", bizId, entry.getInviteePoints(), "受邀注册奖励");
    }

    /**
     * 邀请码只作归属标识，不承担登录或身份授权。
     */
    private String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 唯一索引中保留已删除账号的邀请码占用，但这些邀请码不能继续邀请。
     */
    private UserAccount requireInviter(String code) {
        if (!code.matches("[a-f0-9]{32}")) throw invalidCode();
        UserAccount user = userMapper.findByInviteCode(code);
        if (user == null) throw invalidCode();
        return user;
    }

    /**
     * 独立业务码便于注册页将错误定位到邀请码字段。
     */
    private BizException invalidCode() {
        return new BizException(40021, "邀请码无效，请修改或清空后再注册");
    }

    /**
     * 公开信息与后台审核信息分离，注册页不暴露内部编号和 IP。
     */
    private InvitationInfoVO info(UserAccount user, InvitationSettingsVO rewards) {
        InvitationInfoVO result = new InvitationInfoVO();
        result.setInviteCode(user == null ? null : user.getInviteCode());
        result.setInviterName(user == null ? null : user.getUsername());
        result.setRewards(rewards);
        return result;
    }

    /**
     * 用户端与管理端复用平台分页边界。
     */
    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50) throw new BizException(400, "分页参数无效");
    }
}
