package com.aurora.imagehub.model.vo;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

/**
 * 签到状态和当前规则；奖励数量始终由服务端确定。
 */
@Getter
@Setter
@NoArgsConstructor
public class CheckInVO {

    private LocalDate date;

    private boolean signedIn;

    private int consecutiveDays;

    private int dailyPoints;

    private int bonusPoints;

    /**
     * 未签到时为本次可领积分，已签到时为当天实际到账积分。
     */
    private long rewardPoints;

    /**
     * 下个北京时间零点的时间戳（毫秒），供页面跨天刷新。
     */
    private long nextResetAt;
}
