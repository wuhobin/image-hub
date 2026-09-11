package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.model.vo.ImageStatsVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/** 图片记录持久化；读取、统计和删除均限定 user_id，防止跨用户访问。 */
@Mapper
public interface ImageMapper extends BaseMapper<ImageFile> {
    String FILTER = """
        FROM hub_image WHERE user_id = #{userId}
        <if test="search != null and search != ''">AND LOCATE(LOWER(#{search}), LOWER(name)) > 0</if>
        <if test="type != null and type != ''">AND type = #{type}</if>
        """;


    @Select("SELECT * FROM hub_image WHERE id = #{id} AND user_id = #{userId}")
    ImageFile findOwned(@Param("userId") long userId, @Param("id") String id);

    @Select("<script>SELECT * " + FILTER + " ORDER BY created_at DESC, id DESC</script>")
    Page<ImageFile> list(Page<ImageFile> page, @Param("userId") long userId,
                         @Param("search") String search, @Param("type") String type);

    @Select("SELECT COUNT(*) AS totalCount, COALESCE(SUM(size),0) AS totalBytes FROM hub_image WHERE user_id = #{userId}")
    ImageStatsVO stats(long userId);

    @Delete("DELETE FROM hub_image WHERE id = #{id} AND user_id = #{userId}")
    int deleteOwned(@Param("userId") long userId, @Param("id") String id);
}
