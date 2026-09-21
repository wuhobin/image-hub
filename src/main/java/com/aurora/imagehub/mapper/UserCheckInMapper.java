package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.UserCheckIn;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 按用户读取最近一次有效签到，联合索引支持日期定位。
 */
@Mapper
public interface UserCheckInMapper extends BaseMapper<UserCheckIn> {

    /**
     * 不受数据库会话时区影响，签到日期由业务层统一按北京时间写入。
     */
    @Select("""
            SELECT * FROM hub_user_check_in
            WHERE user_id = #{userId} AND deleted = 0 AND check_in_date = (
                SELECT MAX(check_in_date) FROM hub_user_check_in WHERE user_id = #{userId} AND deleted = 0
            )
            """)
    UserCheckIn latest(long userId);
}
