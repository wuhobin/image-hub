package com.aurora.imagehub.model.vo.admin;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 每日及每满七天的额外奖励金额。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CheckInSettingsVO {

    private int dailyPoints;

    private int bonusPoints;
}
