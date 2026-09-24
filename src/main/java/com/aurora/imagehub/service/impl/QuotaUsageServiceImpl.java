package com.aurora.imagehub.service.impl;

import com.aurora.imagehub.mapper.QuotaUsageMapper;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.model.entity.QuotaUsage;
import com.aurora.imagehub.model.vo.QuotaUsageVO;
import com.aurora.imagehub.service.QuotaUsageService;
import com.aurora.starter.mybatisplus.mybatis.PageUtils;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 统一记录真实消耗；记录失败使图片与 AI 成功终态一起回滚，不产生没有流水的扣额。 */
@Service
public class QuotaUsageServiceImpl extends ServiceImpl<QuotaUsageMapper, QuotaUsage> implements QuotaUsageService {

    /** 积分暂存在 Redis，但计费图片与流水在同一数据库事务内落库，缓存异常可按图片恢复。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordConsumption(ImageFile image) {
        if (!Integer.valueOf(1).equals(image.getQuotaCharged())) {
            throw new IllegalArgumentException("未计费图片不能记录积分消耗");
        }
        QuotaUsage usage = new QuotaUsage();
        usage.setUserId(image.getUserId());
        usage.setScene(switch (image.getSourceType()) {
            case "UPLOAD" -> "IMAGE_UPLOAD";
            case "AI" -> "AI_GENERATION";
            default -> throw new IllegalArgumentException("未知积分消耗场景");
        });
        usage.setBizId(image.getId());
        usage.setAmount(image.getPointsCost());
        usage.setDescription(image.getName());
        if (!save(usage)) throw new IllegalStateException("积分消耗记录保存失败");
    }

    /**
     * 奖励业务与收入必须同事务落库；唯一业务键阻止重复发放，失败整体回滚。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIncome(long userId, String scene, String bizId, int amount, String description) {
        if (amount < 1 || !java.util.Set.of("DAILY_CHECK_IN", "CHECK_IN_BONUS", "INVITATION_REWARD", "INVITEE_REWARD").contains(scene)) {
            throw new IllegalArgumentException("奖励积分或场景无效");
        }
        QuotaUsage usage = new QuotaUsage();
        usage.setUserId(userId);
        usage.setScene(scene);
        usage.setBizId(bizId);
        usage.setAmount(amount);
        usage.setDirection("INCOME");
        usage.setDescription(description);
        if (!save(usage)) throw new IllegalStateException("奖励积分记录保存失败");
    }

    /** 用户 ID 由登录态传入；不接受前端指定归属，不向外暴露实体。 */
    @Override
    public Page<QuotaUsageVO> history(long userId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50) throw new BizException(400, "分页参数无效");
        Page<QuotaUsage> result = page(PageUtils.buildPage(page, pageSize),
                Wrappers.<QuotaUsage>lambdaQuery().eq(QuotaUsage::getUserId, userId)
                        .orderByDesc(QuotaUsage::getCreateTime, QuotaUsage::getId));
        return PageUtils.convert(result, QuotaUsageVO::from);
    }
}
