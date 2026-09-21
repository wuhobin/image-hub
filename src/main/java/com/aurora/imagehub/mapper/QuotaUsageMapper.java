package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.QuotaUsage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 额度流水持久化；业务查询必须限定当前登录用户。 */
@Mapper
public interface QuotaUsageMapper extends BaseMapper<QuotaUsage> {
}
