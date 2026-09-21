package com.aurora.imagehub.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDate;
import java.util.Date;

import lombok.Getter;
import lombok.Setter;

/**
 * 每个用户每天一条签到，记录当日奖励快照；与积分收入同事务保存。
 */
@Getter
@Setter
@TableName("hub_user_check_in")
public class UserCheckIn {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate checkInDate;

    private Integer consecutiveDays;

    private Integer dailyPoints;

    private Integer bonusPoints;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date createTime;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Date updateTime;

    @TableLogic
    private Integer deleted = 0;
}
