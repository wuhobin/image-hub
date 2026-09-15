package com.aurora.imagehub.mapper.admin;

import com.aurora.imagehub.model.entity.admin.SystemSettings;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 全局设置持久化，复用 MyBatis-Plus 的逻辑删除过滤。 */
@Mapper
public interface SystemSettingsMapper extends BaseMapper<SystemSettings> {
}
