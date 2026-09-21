package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.QuotaUsage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 积分流水持久化；业务查询必须限定当前登录用户。 */
@Mapper
public interface QuotaUsageMapper extends BaseMapper<QuotaUsage> {

    /**
     * 奖励长期保存在数据库，缓存重建和普通余额查询都包含已到账收入。
     */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM hub_quota_usage WHERE user_id = #{userId} AND direction = 'INCOME' AND deleted = 0")
    long sumIncome(long userId);
}
