package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.AiModelConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/** 模型持久化；名称占用检查包含逻辑删除记录。 */
@Mapper
public interface AiModelConfigMapper extends BaseMapper<AiModelConfig> {

    @Select("SELECT COUNT(*) FROM hub_ai_model WHERE name = #{name} AND (#{id} IS NULL OR id != #{id})")
    long countName(@Param("name") String name, @Param("id") Long id);

    @Update("UPDATE hub_ai_model SET deleted = 1, enabled = 0, update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted = 0")
    int archive(long id);
}
