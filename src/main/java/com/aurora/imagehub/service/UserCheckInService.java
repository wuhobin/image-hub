package com.aurora.imagehub.service;

import com.aurora.imagehub.model.entity.UserCheckIn;
import com.aurora.imagehub.model.vo.CheckInVO;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 每日签到：北京时间自然日、每七天额外奖励、漏签重计；收入永久累计。
 */
public interface UserCheckInService extends IService<UserCheckIn> {

    /**
     * 返回本人状态；查询不自动签到或发放积分。
     */
    CheckInVO status(long userId);

    /**
     * 用户行锁及唯一键保证跨实例幂等，签到与两类奖励流水同事务提交。
     */
    CheckInVO checkIn(long userId);
}
