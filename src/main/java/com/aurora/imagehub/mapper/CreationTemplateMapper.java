package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.CreationTemplate;
import com.aurora.imagehub.model.entity.ImageFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 模板查询复用原生分页；公开读取只返回上架且分类有效的内容。
 */
@Mapper
public interface CreationTemplateMapper extends BaseMapper<CreationTemplate> {

    /**
     * 搜索使用参数绑定，关键词按字面匹配标题、描述及有效标签。
     */
    @Select("""
            <script>
            SELECT t.* FROM hub_creation_template t
            JOIN hub_template_term c ON c.id=t.category_id AND c.kind='CATEGORY' AND c.deleted=0
            WHERE t.deleted=0
            <if test="publicOnly">AND t.enabled=1</if>
            <if test="enabled != null">AND t.enabled=#{enabled}</if>
            <if test="categoryId != null">AND t.category_id=#{categoryId}</if>
            <if test="tagId != null">AND EXISTS (SELECT 1 FROM hub_template_tag r
              JOIN hub_template_term tag ON tag.id=r.term_id AND tag.deleted=0
              WHERE r.template_id=t.id AND r.term_id=#{tagId} AND r.deleted=0)</if>
            <if test="search != ''">AND (LOCATE(LOWER(#{search}),LOWER(t.title))>0
              OR LOCATE(LOWER(#{search}),LOWER(t.description))>0
              OR EXISTS (SELECT 1 FROM hub_template_tag r JOIN hub_template_term tag ON tag.id=r.term_id
              WHERE r.template_id=t.id AND r.deleted=0 AND tag.deleted=0 AND LOCATE(LOWER(#{search}),LOWER(tag.name))>0))</if>
            ORDER BY t.sort_order, t.id DESC
            </script>
            """)
    Page<CreationTemplate> browse(Page<CreationTemplate> page, @Param("publicOnly") boolean publicOnly,
                                  @Param("enabled") Boolean enabled, @Param("categoryId") Long categoryId,
                                  @Param("tagId") Long tagId, @Param("search") String search);

    /**
     * 写入时锁住模板行，保护图片关联和并发编辑。
     */
    @Select("SELECT * FROM hub_creation_template WHERE id=#{id} AND deleted=0 FOR UPDATE")
    CreationTemplate lock(long id);

    /**
     * 标签批量读取，避免列表逐条查询。
     */
    @Select("""
            <script>SELECT template_id,term_id FROM hub_template_tag WHERE deleted=0 AND template_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    List<Map<String, Object>> tags(@Param("ids") List<Long> ids);

    /**
     * 关联记录保留历史唯一键，新保存时恢复已有关系。
     */
    @Update("UPDATE hub_template_tag SET deleted=1,update_time=CURRENT_TIMESTAMP WHERE template_id=#{id} AND deleted=0")
    void clearTags(long id);

    /**
     * 恢复既有关系，不重复占用唯一键。
     */
    @Update("UPDATE hub_template_tag SET deleted=0,update_time=CURRENT_TIMESTAMP WHERE template_id=#{id} AND term_id=#{tagId}")
    int restoreTag(@Param("id") long id, @Param("tagId") long tagId);

    /**
     * 创建新的标签关联。
     */
    @Insert("INSERT INTO hub_template_tag(template_id,term_id) VALUES(#{id},#{tagId})")
    void addTag(@Param("id") long id, @Param("tagId") long tagId);

    /**
     * 原作品和图片必须同时公开有效，读取定位仅供服务端独立复制。
     */
    @Select("""
            SELECT i.* FROM hub_ai_generation g JOIN hub_image i ON i.id=g.id AND i.user_id=g.user_id
            JOIN hub_user u ON u.id=g.user_id AND u.deleted=0
            WHERE g.share_id=#{shareId} AND g.share_status='PUBLIC' AND g.prompt_public=1
              AND g.status='SUCCEEDED' AND g.deleted=0 AND i.deleted=0 AND i.source_type='AI'
            """)
    ImageFile importImage(String shareId);

}
