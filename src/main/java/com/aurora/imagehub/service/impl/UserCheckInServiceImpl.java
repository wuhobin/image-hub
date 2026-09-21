package com.aurora.imagehub.service.impl;

import com.aurora.imagehub.mapper.UserCheckInMapper;
import com.aurora.imagehub.mapper.UserMapper;
import com.aurora.imagehub.model.entity.UserCheckIn;
import com.aurora.imagehub.model.vo.CheckInVO;
import com.aurora.imagehub.service.QuotaUsageService;
import com.aurora.imagehub.service.UserCheckInService;
import com.aurora.imagehub.service.admin.SystemSettingsService;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 签到及积分收入原子落库；仅锁当前用户，奖励不依赖 Redis 保存。
 */
@Service
@RequiredArgsConstructor
public class UserCheckInServiceImpl extends ServiceImpl<UserCheckInMapper, UserCheckIn> implements UserCheckInService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final UserCheckInMapper userCheckInMapper;

    private final UserMapper userMapper;

    private final QuotaUsageService quotaUsageService;

    private final SystemSettingsService systemSettingsService;

    private final Clock clock;

    /**
     * 连续天数仅保留今天或昨天结束的连续记录，漏签后展示为零。
     */
    @Override
    public CheckInVO status(long userId) {
        return response(today(), userCheckInMapper.latest(userId));
    }

    /**
     * 获得用户行锁后再取日期，等待跨午夜的请求按真正取得执行权的日期签到。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckInVO checkIn(long userId) {
        if (userMapper.lockForCheckIn(userId) == null) throw new BizException(401, "账号不可用，请重新登录");
        LocalDate date = today();
        UserCheckIn latest = userCheckInMapper.latest(userId);
        if (latest != null && !latest.getCheckInDate().isBefore(date)) {
            if (latest.getCheckInDate().isAfter(date)) throw new BizException(409, "签到日期异常，请稍后重试");
            return response(date, latest);
        }
        var rewards = systemSettingsService.checkInRewards();
        UserCheckIn entry = new UserCheckIn();
        entry.setUserId(userId);
        entry.setCheckInDate(date);
        int days = latest != null && latest.getCheckInDate().equals(date.minusDays(1)) ? latest.getConsecutiveDays() + 1 : 1;
        entry.setConsecutiveDays(days);
        entry.setDailyPoints(rewards.getDailyPoints());
        entry.setBonusPoints(days % 7 == 0 ? rewards.getBonusPoints() : 0);
        if (userCheckInMapper.insert(entry) != 1) throw new IllegalStateException("签到保存失败");
        String bizId = date.toString();
        quotaUsageService.recordIncome(userId, "DAILY_CHECK_IN", bizId, entry.getDailyPoints(), "每日签到");
        if (entry.getBonusPoints() > 0) {
            quotaUsageService.recordIncome(userId, "CHECK_IN_BONUS", bizId, entry.getBonusPoints(), "连续签到 " + days + " 天奖励");
        }
        return response(date, entry);
    }

    /**
     * 客户端日期不参与判定，固定按北京时间计算自然日。
     */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZONE);
    }

    /**
     * 已领取金额来自签到快照；后台调价不改写已领取奖励。
     */
    private CheckInVO response(LocalDate date, UserCheckIn entry) {
        var rewards = systemSettingsService.checkInRewards();
        boolean signedIn = entry != null && entry.getCheckInDate().equals(date);
        int days = entry != null && (signedIn || entry.getCheckInDate().equals(date.minusDays(1))) ? entry.getConsecutiveDays() : 0;
        CheckInVO result = new CheckInVO();
        result.setDate(date);
        result.setSignedIn(signedIn);
        result.setConsecutiveDays(days);
        result.setDailyPoints(rewards.getDailyPoints());
        result.setBonusPoints(rewards.getBonusPoints());
        result.setRewardPoints(signedIn ? (long) entry.getDailyPoints() + entry.getBonusPoints()
                : (long) rewards.getDailyPoints() + ((days + 1) % 7 == 0 ? rewards.getBonusPoints() : 0));
        result.setNextResetAt(date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli());
        return result;
    }
}
