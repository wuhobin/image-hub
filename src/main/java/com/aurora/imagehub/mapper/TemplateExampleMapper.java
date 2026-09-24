package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.TemplateExample;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/**
 * 独立示例图片保留存储定位，替换后的清理失败可重试。
 */
@Mapper
public interface TemplateExampleMapper extends BaseMapper<TemplateExample> {

    /**
     * 同一次查询排除当前关联；只存在提交后的完整关联，不会清理并发替换的新图片。
     */
    @Select("""
            SELECT a.* FROM hub_template_example a JOIN hub_creation_template t ON t.id=a.template_id AND t.deleted=0
            WHERE a.template_id=#{id} AND a.deleted=0 AND (t.example_id IS NULL OR a.id != t.example_id)
            """)
    java.util.List<TemplateExample> unused(long id);

    /**
     * 云文件删除成功后才标记删除。
     */
    @Update("UPDATE hub_template_example SET deleted=1, update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted=0")
    int archive(String id);
}
