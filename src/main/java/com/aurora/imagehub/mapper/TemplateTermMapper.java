package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.TemplateTerm;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/**
 * 分类和标签使用同一受控词表；名称占用包含已删除记录。
 */
@Mapper
public interface TemplateTermMapper extends BaseMapper<TemplateTerm> {

    /**
     * 保存模板及删除词条共用行锁，防止挂上刚删除的分类或标签。
     */
    @Select("SELECT * FROM hub_template_term WHERE id=#{id} AND deleted=0 FOR UPDATE")
    TemplateTerm lock(long id);

    /**
     * 唯一性检查仍包含已删除词条。
     */
    @Select("SELECT COUNT(*) FROM hub_template_term WHERE kind=#{kind} AND name=#{name} AND (#{id} IS NULL OR id != #{id})")
    long occupied(@Param("kind") String kind, @Param("name") String name, @Param("id") Long id);

    /**
     * 删除前要求所有有效模板解除关联，包括草稿。
     */
    @Select("""
            SELECT COUNT(*) FROM hub_creation_template t WHERE t.deleted=0 AND
            (t.category_id=#{id} OR EXISTS (SELECT 1 FROM hub_template_tag r
            WHERE r.template_id=t.id AND r.term_id=#{id} AND r.deleted=0))
            """)
    long usages(long id);

    /**
     * 逻辑删除同步更新审计时间。
     */
    @Update("UPDATE hub_template_term SET deleted=1, update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted=0")
    int archive(long id);
}
